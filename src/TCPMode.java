import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import javax.xml.crypto.Data;

public class TCPMode {
    
    final String mode; // sender or receiver

    protected String remoteIP;
    protected String port;
    protected String remotePort;
    protected String fileName;
    protected int mtu;
    protected int sws;

    static protected String SEND_MODE = "send";
    static protected String REC_MODE = "receive";

    enum TcpStates {
        //sender and sender+receiver states
        CLOSED,         // No connection
        SYN_SENT,       // Sent SYN, waiting for SYN-ACK
        ESTABLISHED,    // Connection open
        FIN_WAIT_1,     // Sent FIN, waiting for ACK
        FIN_WAIT_2,     // Got ACK, waiting for receiver's FIN
        TIME_WAIT,       // Sent final ACK, waiting to ensure receiver received
        
        //receiver states
        LISTEN,         // Waiting for an incoming SYN
        SYN_RECEIVED,   // Received SYN, sent SYN-ACK, waiting for ACK
        CLOSE_WAIT,     // Received FIN from sender, waiting to send own FIN
        LAST_ACK,       // Sent own FIN, waiting for final ACK
    }

    /**
     * Constructor for sender
     * @param mode
     * @param port
     * @param remoteIP
     * @param remotePort
     * @param fileName
     * @param mtu
     * @param sws
     */
    public TCPMode(String port, String remoteIP, String remotePort, String fileName, String mtu, String sws){
        this.mode = SEND_MODE;
        this.port = port;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
        this.fileName = fileName;
        this.mtu = Integer.parseInt(mtu);
        this.sws =  Integer.parseInt(sws);
    }

/**
 * Receiver Mode
 * 
 * @param port
 * @param mtu
 * @param sws
 * @param fileName
 */
    public TCPMode(String port, String mtu, String sws, String fileName){
        this.mode = REC_MODE;
        this.port = port;
        this.mtu = Integer.parseInt(mtu);
        this.sws = Integer.parseInt(sws);
        this.fileName = fileName;
    }


    public void run(){

        //sender logic 
        if (mode.equals(SEND_MODE)){

            //set initial state
            TcpStates state = TcpStates.CLOSED;
            System.out.println("Sender initialization");

            try {
                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));

                FileInputStream fis = new FileInputStream(fileName);
                
                byte[] buffer = new byte[mtu];
                int bytesRead;

                //initiation - 3 way hand shake
                //CLOSED to SYN_ACK
                while(state == TcpStates.CLOSED|| state == TcpStates.SYN_SENT){

                    byte[] emptyBuffer = new byte[0];

                    //step 1
                    if (state == TcpStates.CLOSED){
                        TCPPacket tPacket = new TCPPacket(mtu, 0, 0, true, true, false, emptyBuffer);  //TODO verify initial seq and ackNUM
                        
                        sendPacket(tPacket, mode,  socket, remoteIP);
                        
                        state = TcpStates.SYN_SENT;
                    }

                    // step 3
                    if (state == TcpStates.SYN_SENT){

                        TCPPacket recPacket = receivePacket(emptyBuffer, socket);

                        byte[] tcpData = recPacket.getPayload();                        
                        
                        TCPPacket recTCPPacket = new TCPPacket(this.mtu, tcpData).deserialize();

                        //Rec packet conditions: S, A, seqnum 0, Ack 1
                        if (recTCPPacket.getSynFlag() && recTCPPacket.getAckFlag() == true && recTCPPacket.getSeqNum() == 0 && recTCPPacket.getAckNum() == 1){

                            state = TcpStates.ESTABLISHED;
                        }
                    }
                }

                while ((bytesRead = fis.read(buffer)) != -1) {

                    byte[] payload = Arrays.copyOf(buffer, bytesRead);

                    System.out.println("sending packet");
                    System.out.println("Bytes read from file: " + bytesRead);
                    System.out.println("Payload (first few bytes): " + Arrays.toString(Arrays.copyOf(payload, Math.min(10, payload.length))));


                    //break if buffer not full
                    if (bytesRead < buffer.length) {
                        break;
                    }
                }
            
            //close and clean
            fis.close();
            //TODO remove when TCP closing protocol is finalized
            DatagramPacket endPacket = new DatagramPacket(new byte[0], 0, InetAddress.getByName(remoteIP), Integer.parseInt(remotePort));
            socket.send(endPacket);
            socket.close();

            } catch (UnknownHostException e) {
                e.printStackTrace();
                System.exit(1);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
        
        //Receiver mode
        if (mode.equals(REC_MODE)){

            TcpStates state = TcpStates.LISTEN;

            try {
                System.out.println("Receiver on port: " + port);

                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));
                byte[] buffer = new byte[mtu];                

                //handshake initialization
                while (state == TcpStates.LISTEN || state == TcpStates.SYN_RECEIVED) {
                    
                    TCPPacket recPacket = receivePacket(buffer, socket);

                    byte[] tcpData = recPacket.getPayload();


                    if (recPacket.getSynFlag() == true && recPacket.getAckFlag() == false && recPacket.getSeqNum()== 0 && recPacket.getAckNum() == 0){
                        
                        state = TcpStates.SYN_RECEIVED;

                        TCPPacket sendTcpPacket = new TCPPacket(mtu, 0, 1, true, true, false, tcpData);

                        sendPacket(sendTcpPacket, mode, socket, remoteIP);
                    }

                    //move from SYN_RECEIVED TO ESTABLISHED
                    if (recPacket.getSynFlag() == false && recPacket.getAckFlag() == true && recPacket.getSeqNum() == 1 && recPacket.getAckNum() == 1){
                        state = TcpStates.ESTABLISHED;
                    }
                }

                //Established phase - receive data
                FileOutputStream fos = new FileOutputStream(fileName);

                while (state == TcpStates.ESTABLISHED) {

                    TCPPacket tPacket = receivePacket(buffer, socket);

                    //TODO write to buffer and wait until end to write to file?
                    fos.write(tPacket.getPayload(), 0, tPacket.getPayloadLength());
                }
                
                fos.close();
                socket.close();

            } catch (IOException e){
                System.out.println("Error listening on port: " + e);
			}
            finally {
                
                System.exit(0);
            }

        }
    }

    /**
     * Helper method for sending packets
     * @param payload
     * @param socket
     * @param remoteIP
     * @throws IOException
     */
    private void sendPacket(TCPPacket payload, String mode, DatagramSocket socket, String remoteIP) throws IOException{

        byte[] serialized = payload.serialize();
        System.out.println("Serialized packet length: " + serialized.length);
        DatagramPacket packet = new DatagramPacket(serialized, 0, serialized.length, InetAddress.getByName(remoteIP), Integer.parseInt(remotePort));

        System.out.println("Sending packet to " + remoteIP + ":" + remotePort);

        System.out.println(toString(mode, payload));
        socket.send(packet);

    }

    /**
     * Helper method for  receiving packets
     * @param buffer
     * @param socket
     * @return Datagram packet for further processing
     * @throws IOException
     */
    private TCPPacket receivePacket(byte[] buffer, DatagramSocket socket) throws IOException{

        System.out.println("Receiving packet");

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    
        socket.receive(packet);

        System.out.println("[Receiver] Packet received from " + packet.getAddress().getHostAddress() + ":" + packet.getPort());

        System.out.println("[Receiver] Packet length w header: " + packet.getLength());

        byte[] data = packet.getData();                    

        TCPPacket tPacket = new TCPPacket(mtu, data).deserialize();

        return tPacket;
    }

    /**
     * Helper method for sending ACKs
     * @param packet - for extracting sender's host information
     * @param tPacket -for extracting TCP packet information
          * @throws IOException 
          */
         private void sendAck(DatagramPacket packet, DatagramSocket socket, TCPPacket tPacket, TcpStates state) throws IOException{
        //destination information
        InetAddress senderAddress = packet.getAddress();
        int senderPort = packet.getPort();

        //TCP Packet fields
        int seqNum;
        int ackNum;
        boolean synFlag = false;
        boolean ackFlag = true;
        boolean finFlag = false;

        if ((state == TcpStates.CLOSED)){
            ackNum = 0;
        }
        else{
            ackNum = tPacket.getSeqNum() + 1;
        }
       
        //seqNum 0 for initation and 
        if (state == TcpStates.CLOSED || state == TcpStates.SYN_SENT){
            seqNum = 0;
            synFlag = true;

        }
        else{
            seqNum = tPacket.getSeqNum();
        }

        TCPPacket tcpAckPacket = new TCPPacket(0, seqNum, ackNum, synFlag, ackFlag, finFlag, null);

        DatagramPacket sendPacket = new DatagramPacket(tcpAckPacket.serialize(), tcpAckPacket.getOverallLength(), senderAddress, senderPort);

        socket.send(sendPacket);

    }

    public String toString(String mode, TCPPacket packet) {
        // TODO Auto-generated method stub

        String sndRec = "";
        
        if (mode.equals(SEND_MODE)){
            sndRec = "snd";
        }

        if (mode.equals(REC_MODE)){
            sndRec = "rcv";
        }

        String output = sndRec + " " + System.nanoTime() + " " + packet.toString();

        return output;
    }
}
