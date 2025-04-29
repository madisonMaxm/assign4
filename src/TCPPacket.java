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
    protected int overallLength; 
    protected byte[] payload;
    protected boolean synFlag;
    protected boolean ackFlag;
    protected boolean finFlag;
    protected short checksum;
    protected byte[] serialPacket;
    
    public TCPPacket(int seqNum, int ackNum, boolean syn, boolean ack, boolean fin, byte[] payload, long timeStamp){
        if (payload == null){
            this.payload = new byte[0];
            //System.out.println( " payload length 0");
        }
        else{
            this.payload = payload;
        }
        
        this.seqNum = seqNum;
        this.lengthField = this.payload.length;
        this.overallLength = this.payload.length + 24;
        this.ackNum = ackNum;
        this.synFlag = syn;
        this.ackFlag = ack;
        this.finFlag = fin;
        this.timeStamp = timeStamp;
    }

    public TCPPacket(byte[] serialPacket){
        
        this.serialPacket = serialPacket;
        this.overallLength = serialPacket.length;
        //System.out.println("constructor overall length: " + serialPacket.length);
    }

    public void setSeqNum(int value){
        this.seqNum = value;
    }

    public void setAckNum (int value){
        this.ackNum = value;
    }

    public boolean getSynFlag(){
        return this.synFlag;
    }

    public boolean getAckFlag(){
        return this.ackFlag;
    }

    public boolean getFinFlag(){
        return this.finFlag;
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

    public int getOverallLength(){
        return this.overallLength;
    }

    public int getPayloadLength(){
        return this.payload.length;
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
        bb.putShort((short) 0); //zero checksum field before calculating

        //add data to packet
        bb.put(this.payload);

        if (this.checksum == 0) {
            bb.rewind();
            int accumulation = 0;
            for (int i = 0; i < overallLength/2; ++i) {
                accumulation += 0xffff & bb.getShort();
            }
            accumulation = ((accumulation >> 16) & 0xffff)
                    + (accumulation & 0xffff);
            this.checksum = (short) (~accumulation & 0xffff);
            bb.putShort(22, this.checksum); 
        }
        
        return data;
    }

    /**
     * 
     * @param data - TCP packet (for this assignment, the UDP payload)
     * @param mtu - maximum payload size
     * @return - TCPPacket with deserialized data
     */
    public TCPPacket deserialize() {

        //System.out.println("deserializing TCPPacket");

        byte headerLength = 24; //based on 6x32 bit words

        ByteBuffer bb = ByteBuffer.wrap(serialPacket);
        this.seqNum = bb.getInt();
        this.ackNum = bb.getInt();
        this.timeStamp = bb.getLong();
        int rawLengthField = bb.getInt();
        this.lengthField = rawLengthField >> 3;
        this.synFlag = (rawLengthField & 0b001) != 0;
        this.ackFlag = (rawLengthField & 0b010) != 0;
        this.finFlag = (rawLengthField & 0b100) != 0;
        
        //int payloadLength = Math.max(0, overallLength - 24); // remove header size

        bb.getShort(); // skip 2 bytes

        this.checksum = bb.getShort();
        int payloadLength = serialPacket.length - 24;
        this.payload = new byte[payloadLength];
        if (payloadLength > 0) {
            bb.get(this.payload);
        }

        /** Debugging output
        System.out.println("Payload Length: " + this.lengthField);
        System.out.println("Header Length: " + headerLength);
        System.out.println("Total Packet Length: " + overallLength);
        */

        byte[] packetCopy = new byte[serialPacket.length];
        System.arraycopy(serialPacket, 0, packetCopy, 0, serialPacket.length);
        packetCopy[22] = 0;
        packetCopy[23] = 0;

        //calculate checksum
        ByteBuffer headerBuffer = ByteBuffer.wrap(packetCopy);
        int accumulation = 0;
		for (int i = 0; i < lengthField/2; ++i) {
			accumulation += 0xffff & headerBuffer.getShort();
		}
		accumulation = ((accumulation >> 16) & 0xffff)
				+ (accumulation & 0xffff);
		int checksumAcc = (short) (accumulation & 0xffff);

        int checksumVer = this.checksum + checksumAcc;

        if (checksumVer == -1){
            return this;
        }
        else{
            return null;
        }

        
    }


    @Override
    public String toString() {

        String synFlag = this.synFlag ? "S" : "-";
        String ackFlag = this.ackFlag ? "A" : "-";
        String finFlag = this.finFlag ? "F" : "-";

        String output = synFlag + " " + ackFlag + " " + finFlag + " " + (this.getPayloadLength() == 0 ? "-" : "D")  + " " + this.seqNum + " " + this.payload.length + " " + this.ackNum;

        return output;
    }
}