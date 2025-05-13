import java.net.*;
import java.nio.ByteBuffer;

public class TCPPacket {

    protected int seqNum;
    protected int ackNum;
    protected long timeStamp;
    protected int lengthField;
    protected int overallLength;
    protected byte[] payload;
    protected boolean synFlag;
    protected boolean ackFlag;
    protected boolean finFlag;
    protected short checksum;
    protected byte[] serialPacket;

    public TCPPacket(int seqNum, int ackNum, boolean syn, boolean ack, boolean fin, byte[] payload, long timeStamp) {
        this.payload = (payload != null) ? payload : new byte[0];
        this.seqNum = seqNum;
        this.lengthField = this.payload.length;
        this.overallLength = this.payload.length + 24;
        this.ackNum = ackNum;
        this.synFlag = syn;
        this.ackFlag = ack;
        this.finFlag = fin;
        this.timeStamp = timeStamp;
    }

    public TCPPacket(byte[] serialPacket) {
        this.serialPacket = serialPacket;
        this.overallLength = serialPacket.length;
    }

    public void setSeqNum(int value) { this.seqNum = value; }

    public void setAckNum(int value) { this.ackNum = value; }

    public boolean getSynFlag() { return this.synFlag; }

    public boolean getAckFlag() { return this.ackFlag; }

    public boolean getFinFlag() { return this.finFlag; }

    public int getSeqNum() { return this.seqNum; }

    public int getAckNum() { return this.ackNum; }

    public long getTimeStamp() { return this.timeStamp; }

    public byte[] getPayload() { return this.payload; }

    public int getOverallLength() { return this.overallLength; }

    public int getPayloadLength() { return this.payload.length; }

    public byte[] serialize() {
        byte headerLength = 24;
        int overallLength = headerLength + payload.length;
        byte[] data = new byte[overallLength];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putInt(this.seqNum);
        bb.putInt(this.ackNum);
        bb.putLong(this.timeStamp);

        this.lengthField = overallLength << 3;
        if (synFlag) lengthField |= 0b001;
        if (ackFlag) lengthField |= 0b010;
        if (finFlag) lengthField |= 0b100;

        bb.putInt(lengthField);
        bb.putShort((short) 0);  // Reserved / unused
        bb.putShort((short) 0);  // Placeholder for checksum

        bb.put(this.payload);

        // Compute checksum
        byte[] checksumData = data.clone();
        checksumData[22] = 0;
        checksumData[23] = 0;

        int accumulation = 0;
        ByteBuffer checksumBB = ByteBuffer.wrap(checksumData);
        int i = 0;
        while (i + 1 < checksumData.length) {
            accumulation += 0xFFFF & checksumBB.getShort();
            i += 2;
        }
        if (checksumData.length % 2 != 0) {
            accumulation += (checksumData[checksumData.length - 1] & 0xFF) << 8;
        }
        accumulation = ((accumulation >> 16) & 0xFFFF) + (accumulation & 0xFFFF);
        this.checksum = (short) (~accumulation & 0xFFFF);

        // Insert checksum into serialized data
        ByteBuffer.wrap(data).putShort(22, this.checksum);

        return data;
    }

    public TCPPacket deserialize() {
        byte headerLength = 24;
        ByteBuffer bb = ByteBuffer.wrap(serialPacket);
        this.seqNum = bb.getInt();
        this.ackNum = bb.getInt();
        this.timeStamp = bb.getLong();

        int rawLengthField = bb.getInt();
        this.lengthField = rawLengthField >> 3;
        this.synFlag = (rawLengthField & 0b001) != 0;
        this.ackFlag = (rawLengthField & 0b010) != 0;
        this.finFlag = (rawLengthField & 0b100) != 0;

        bb.getShort();  // Reserved
        this.checksum = bb.getShort();

        int payloadLength = serialPacket.length - headerLength;
        this.payload = new byte[Math.max(0, payloadLength)];
        if (payloadLength > 0) {
            bb.get(this.payload);
        }

        // Prepare for checksum verification
        byte[] packetCopy = serialPacket.clone();
        packetCopy[22] = 0;
        packetCopy[23] = 0;

        int accumulation = 0;
        ByteBuffer checksumBB = ByteBuffer.wrap(packetCopy);
        int i = 0;
        while (i + 1 < packetCopy.length) {
            accumulation += 0xFFFF & checksumBB.getShort();
            i += 2;
        }
        if (packetCopy.length % 2 != 0) {
            accumulation += (packetCopy[packetCopy.length - 1] & 0xFF) << 8;
        }
        accumulation = ((accumulation >> 16) & 0xFFFF) + (accumulation & 0xFFFF);
        int checksumAcc = (short) (accumulation & 0xFFFF);
        int checksumVer = this.checksum + checksumAcc;

        return (checksumVer == -1) ? this : null;
    }

    @Override
    public String toString() {
        String synFlag = this.synFlag ? "S" : "-";
        String ackFlag = this.ackFlag ? "A" : "-";
        String finFlag = this.finFlag ? "F" : "-";
        return synFlag + " " + ackFlag + " " + finFlag + " " +
               (this.getPayloadLength() == 0 ? "-" : "D") + " " +
               this.seqNum + " " + this.payload.length + " " + this.ackNum;
    }
}
