/**
 * TCPPacket builds synthetic TCP functionality for transport by UDP
 * 
 * @author Maxwell Meller
 */

import java.net.*;
import java.nio.ByteBuffer;

public class TCPPacket {

    protected int seqNum; //4 byte
    protected int ackNum; // 4 byte
    protected long timeStamp; //8 byte
    protected int lengthField; //4 byte
    protected byte[] payload;
    protected boolean synFlag;
    protected boolean ackFlag;
    protected boolean finFlag;
    protected short checksum;
    protected byte[] serialPacket;
    
    public TCPPacket(int mtu, int ackNum, boolean syn, boolean ack, boolean fin, byte[] payload){
        this.lengthField = mtu;
        this.ackNum = ackNum;
        this.synFlag = syn;
        this.ackFlag = ack;
        this.finFlag = fin;
        this.timeStamp = System.nanoTime();
        this.payload = payload;
    }

    public TCPPacket(int mtu, byte[] serialPacket){
        this.lengthField = mtu;
        this.serialPacket = serialPacket;
    }

    public void setSeqNum(int value){
        this.seqNum = value;
    }

    public void setAckNum (int value){
        this.ackNum = value;
    }

    public int getSeqNum(){
        return this.seqNum;
    }
    
    public int getAckNum(){
        return this.ackNum;
    }

    public long getTimeStamp(){
        return this.timeStamp;
    }

    public byte[] getPayload(){
        return this.payload;
    }

    /**
     * Serializes all data from TCP Packet
     * @return byte array
     */
    public byte[] serialize() {
        
        
        byte headerLength = 24; //based on 6x32 bit words
        int overallLength = headerLength + payload.length;

        byte[] data = new byte[overallLength];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putInt(this.seqNum);
        bb.putInt(this.ackNum);
        bb.putLong(this.timeStamp);

        this.lengthField = overallLength << 3; //shift 3 bits 
        if (synFlag) lengthField |= 0b001; // Set SYN flag (bit 0)
        if (ackFlag) lengthField |= 0b010; // Set ACK flag (bit 1)
        if (finFlag) lengthField |= 0b100; // Set FIN flag (bit 2)

        bb.putInt(lengthField);

        byte[] zeros = new byte[2];
        bb.put(zeros); //16 bits of 0s

        // compute checksum if needed
        /*/
         if (this.checksum == 0) {
            bb.rewind();
            int accumulation = 0;
            for (int i = 0; i < headerLength * 2; ++i) {
                accumulation += 0xffff & bb.getShort();
            }
            accumulation = ((accumulation >> 16) & 0xffff)
                    + (accumulation & 0xffff);
            this.checksum = (short) (~accumulation & 0xffff);
            bb.putShort(22, this.checksum); //TODO is the index correct?
        }
        */
        //add data to packet
        bb.put(this.payload);

        return data;
    }

    /**
     * 
     * @param data - TCP packet (for this assignment, the UDP payload)
     * @param mtu - maximum payload size
     * @return - TCPPacket with deserialized data
     */
    public TCPPacket deserialize() {

        byte headerLength = 24; //based on 6x32 bit words
        int overallLength = this.lengthField + 24;;

        ByteBuffer bb = ByteBuffer.wrap(serialPacket);
        this.seqNum = bb.getInt();
        this.ackNum = bb.getInt();
        this.timeStamp = bb.getLong();
        int rawLengthField = bb.getInt();
        this.synFlag = (rawLengthField & 0b001) != 0;
        this.finFlag = (rawLengthField & 0b010) != 0;
        this.ackFlag = (rawLengthField & 0b100) != 0;
        this.lengthField = rawLengthField >>> 3 >> 3; //length in bytes?
        bb.getShort(); // skip 2 bytes

        this.checksum = bb.getShort();
        this.payload = new byte[Math.min(this.lengthField, overallLength - headerLength)];
        bb.get(this.payload, 0, this.payload.length);

        return this;
    }

    //todo implement checksum
}
