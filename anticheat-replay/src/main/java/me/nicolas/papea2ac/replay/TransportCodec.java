package me.nicolas.papea2ac.replay;
import me.nicolas.papea2ac.schema.TransportSnapshot;
import java.io.*;
import java.util.*;
/** Bounded explicit codec; never Java object deserialization. Caller owns I/O scheduling. */
public final class TransportCodec {
    private TransportCodec(){}
    public static byte[] encode(TransportSnapshot s)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(0x50414331);out.writeInt(s.schema());out.writeLong(s.epoch());out.writeLong(s.processed());out.writeLong(s.certified());
            out.writeUTF(s.numericProfile());out.writeInt(s.pending().size());
            for(var p:s.pending()){out.writeLong(p.attemptId());out.writeUTF(p.digest());out.writeLong(p.expiryNanos());}
        }
        return bytes.toByteArray();
    }
    public static TransportSnapshot decode(byte[] bytes,String requiredNumericProfile)throws IOException{
        if(bytes.length>65536)throw new IOException("oversize");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=0x50414331)throw new IOException("magic");
            int schema=in.readInt();long epoch=in.readLong(),processed=in.readLong(),certified=in.readLong();
            String numeric=in.readUTF();if(!numeric.equals(requiredNumericProfile))throw new IOException("incompatible numeric environment");
            int count=in.readInt();if(count<0||count>256)throw new IOException("count");
            List<TransportSnapshot.Pending> pending=new ArrayList<>(count);
            for(int i=0;i<count;i++)pending.add(new TransportSnapshot.Pending(in.readLong(),in.readUTF(),in.readLong()));
            if(in.available()!=0)throw new IOException("trailing bytes");
            return new TransportSnapshot(schema,epoch,processed,certified,pending,numeric);
        }catch(IllegalArgumentException e){throw new IOException("invalid schema/entry",e);}
    }
}
