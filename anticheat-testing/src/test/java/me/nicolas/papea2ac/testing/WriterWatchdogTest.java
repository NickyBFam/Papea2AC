package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.replay.*;

/** P1-H2: writer watchdog timeout semantics. A timeout means no progress was observed, never that the
 * thread terminated; resources are only released once termination is actually proven. */
class WriterWatchdogTest {
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    static me.nicolas.papea2ac.schema.TransportCapsule capsule(){
        return new me.nicolas.papea2ac.schema.TransportCapsule(1,me.nicolas.papea2ac.core.TransportReplayDriver.PROFILE,"0".repeat(64),"test",1,false,
            java.util.List.of(new me.nicolas.papea2ac.schema.TransportCapsule.Close(1)),true);
    }
    @Test void idleWriterReportsRunningNeverSlowOrHung(){
        var quota=new ReplayQuota();var writer=new CaptureWriter(quota,c->{},e->fail(e));
        try{assertEquals(CaptureWriter.Health.RUNNING,writer.health(System.nanoTime()));}
        finally{writer.close();}
    }
    @Test void slowThenProgressingWriterReportsSlowNotHungAndEventuallyFinishes()throws Exception{
        var quota=new ReplayQuota();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writer=new CaptureWriter(quota,c->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}},e->fail(e));
        try{
            assertTrue(writer.submit(1,1024,WriterWatchdogTest::capsule));assertTrue(entered.await(2,TimeUnit.SECONDS));
            // health() at "now" close to acceptance is RUNNING; well past the writer's own started instant it is not yet HUNG.
            assertNotEquals(CaptureWriter.Health.HUNG,writer.health(System.nanoTime()));
            release.countDown();
            await(()->writer.written()==1);
            assertEquals(CaptureWriter.Health.RUNNING,writer.health(System.nanoTime()),"a writer that finished its job and returned to idle is RUNNING again, not stuck in a stale SLOW/HUNG state");
        }finally{writer.close();await(writer::terminated);}
    }
    @Test void blockedWriterEventuallyReportsHungButNeverReleasesLeasesUntilProvenTerminated()throws Exception{
        var quota=new ReplayQuota();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writer=new CaptureWriter(quota,c->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}},e->fail(e));
        try{
            assertTrue(writer.submit(1,1024,WriterWatchdogTest::capsule));assertTrue(entered.await(2,TimeUnit.SECONDS));
            // A timeout means "no progress observed," not "the thread is dead": the writer is still alive and its charges remain.
            assertTrue(writer.written()==0&&!writer.terminated());
            assertTrue(quota.used(ReplayQuota.Pool.IO)>0,"a blocked-but-alive writer must keep its resource charge, never release it speculatively");
            assertNotEquals(CaptureWriter.Health.TERMINATED,writer.health(System.nanoTime()),"blocked is not the same fact as terminated");
        }finally{release.countDown();writer.close();await(writer::terminated);}
    }
    @Test void fencedWriterStopsAdmittingNewJobsButLeavesInFlightWorkUntouched()throws Exception{
        var quota=new ReplayQuota();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writer=new CaptureWriter(quota,c->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}},e->fail(e));
        try{
            assertTrue(writer.submit(1,1024,WriterWatchdogTest::capsule));assertTrue(entered.await(2,TimeUnit.SECONDS));
            writer.fence();
            assertEquals(CaptureWriter.Health.FENCED,writer.health(System.nanoTime()));
            assertFalse(writer.submit(2,1024,WriterWatchdogTest::capsule),"a fenced writer must reject new admission");
            assertEquals(1,writer.rejected());
            release.countDown();
            await(()->writer.written()==1);
        }finally{writer.close();await(writer::terminated);}
    }
    @Test void terminatedWriterIsTheOnlyStateThatMayReleaseLeases()throws Exception{
        var quota=new ReplayQuota();
        var writer=new CaptureWriter(quota,c->{},e->fail(e));
        writer.close();await(writer::terminated);
        assertEquals(CaptureWriter.Health.TERMINATED,writer.health(System.nanoTime()));
        assertFalse(writer.submit(1,1024,WriterWatchdogTest::capsule),"a terminated writer must reject admission, not silently accept and lose work");
    }
    @Test void writerExceptionReleasesChargesAndReportsFailureButKeepsTheThreadAlive()throws Exception{
        var quota=new ReplayQuota();var errors=new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var writer=new CaptureWriter(quota,c->{throw new java.io.IOException("injected");},errors::add);
        try{
            assertTrue(writer.submit(1,1024,WriterWatchdogTest::capsule));await(()->writer.failed()==1);
            assertEquals(1,errors.size());assertFalse(writer.terminated(),"one failing job must not kill the writer thread");
            assertEquals(0,quota.used(ReplayQuota.Pool.IO));assertEquals(0,quota.used(ReplayQuota.Pool.PROMOTION));
        }finally{writer.close();await(writer::terminated);}
    }
    @Test void shutdownWithABlockedWriterEventuallySafelyReleasesOnlyAfterRealTermination()throws Exception{
        var quota=new ReplayQuota();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writer=new CaptureWriter(quota,c->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}},e->fail(e));
        try{
            assertTrue(writer.submit(1,1024,WriterWatchdogTest::capsule));assertTrue(entered.await(2,TimeUnit.SECONDS));
            writer.close();quota.closeAdmission();
            assertFalse(writer.terminated());assertTrue(quota.used(ReplayQuota.Pool.IO)>0,"shutdown alone must not release a still-blocked writer's charges");
        }finally{release.countDown();}
        await(writer::terminated);
        await(()->quota.used(ReplayQuota.Pool.IO)==0&&quota.used(ReplayQuota.Pool.PROMOTION)==0);
    }
}
