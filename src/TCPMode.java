import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TreeMap;

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

    int seqNum = 0;
    int ackNum = 0;

    TcpStates state = TcpStates.CLOSED; //initialize with closed

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
            System.out.println("Sender initialization");

            try {
                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));

                FileInputStream fis = new FileInputStream(fileName);
                
                byte[] buffer = new byte[mtu];
                int bytesRead;

                //initiation - 3 way hand shake
                //CLOSED to SYN_ACK
                while(state == TcpStates.CLOSED || state == TcpStates.SYN_SENT){

                    //step 1
                    if (state == TcpStates.CLOSED){
                        TCPPacket tPacket = new TCPPacket(seqNum, ackNum, true, false, false, null, System.nanoTime()); 
                        
                        seqNum++;

                        sendPacket(tPacket, socket, remoteIP);

                        state = TcpStates.SYN_SENT;
                        System.out.println( "receiver state " + state);
                    }

                    // step 3
                    if (state == TcpStates.SYN_SENT){

                        TCPPacket recPacket = receivePacket(socket);
                        
                        //Rec packet conditions: S, A, seqnum 0, Ack 1
                        if (recPacket.getSynFlag() && recPacket.getAckFlag() == true && recPacket.getSeqNum() == 0 && recPacket.getAckNum() == 1){

                            state = TcpStates.ESTABLISHED;
                            
                            TCPPacket tPacket = new TCPPacket(seqNum, recPacket.getSeqNum() + 1, false, true, false, null, System.nanoTime()); 
                            
                            sendPacket(tPacket, socket, remoteIP);
                        }
                    }
                }
                /**
                while ((bytesRead = fis.read(buffer)) != -1) {

                    byte[] payload = Arrays.copyOf(buffer, bytesRead);

                    System.out.println("sending packet");
                    System.out.println("Bytes read from file: " + bytesRead);
                    System.out.println("Payload (first few bytes): " + Arrays.toString(Arrays.copyOf(payload, Math.min(10, payload.length))));


                    //break if buffer not full
                    if (bytesRead < buffer.length) {
                        break;
                    }
                }*/

            sendData(fis, socket);

            //close and clean
            fis.close();
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

            state = TcpStates.LISTEN;
            int receiverSeqNum = 0;

            try {
                System.out.println("Receiver on port: " + port);

                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));
                byte[] buffer = new byte[mtu];
                

                //handshake initialization
                while (state == TcpStates.LISTEN || state == TcpStates.SYN_RECEIVED) {
                    
                    TCPPacket recPacket = receivePacket(socket);

                    if (recPacket.getSynFlag() == true && recPacket.getAckFlag() == false && recPacket.getSeqNum() == 0 && recPacket.getAckNum() == 0){

                        System.out.println("state -> SYN_RECEIVED");
                        
                        state = TcpStates.SYN_RECEIVED;

                        TCPPacket sendTcpPacket = new TCPPacket(receiverSeqNum, recPacket.getSeqNum() + 1, true, true, false, null, recPacket.getTimeStamp());
                        receiverSeqNum++;

                        sendPacket(sendTcpPacket, socket, remoteIP);
                    }

                    recPacket = receivePacket(socket);

                    //move from SYN_RECEIVED TO ESTABLISHED
                    if (recPacket.getSynFlag() == false && recPacket.getAckFlag() == true && recPacket.getPayloadLength() == 0 && recPacket.getSeqNum() == 1 && recPacket.getAckNum() == 1){
                        state = TcpStates.ESTABLISHED;
                    }
                }

                //Established phase - receive data
                FileOutputStream fos = new FileOutputStream(fileName);

                receiveData(socket, seqNum, fos);

                //state is CLOSED                
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
    private void sendPacket(TCPPacket payload, DatagramSocket socket, String remoteIP) throws IOException{

        byte[] serialized = payload.serialize();
        System.out.println("Serialized packet length: " + serialized.length);
        DatagramPacket packet = new DatagramPacket(serialized, 0, serialized.length, InetAddress.getByName(remoteIP), Integer.parseInt(remotePort));

        System.out.println("Sending packet to " + remoteIP + ":" + remotePort);

        System.out.println(toString("send", payload));
        socket.send(packet);

    }

    /**
     * Helper method for  receiving packets
     * @param buffer
     * @param socket
     * @return Datagram packet for further processing
     * @throws IOException
     */
    private TCPPacket receivePacket(DatagramSocket socket) throws IOException{

        System.out.println("Receiving packet");

        byte[] datagramBuffer = new byte[1500];

        DatagramPacket packet = new DatagramPacket(datagramBuffer, datagramBuffer.length);
                    
        socket.receive(packet);

        this.remoteIP = packet.getAddress().getHostAddress().toString();//set sender IP address
        this.remotePort = String.valueOf(packet.getPort());


        ///System.out.println("[Receiver] Packet received from " + packet.getAddress().getHostAddress() + ":" + packet.getPort());

        //System.out.println("[Receiver] Packet length w header: " + packet.getLength());

        byte[] data = packet.getData();   
        byte[] receivedData = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

        TCPPacket tPacket = new TCPPacket(receivedData).deserialize();

        System.out.println(toString("receive", tPacket));

        return tPacket;
    }

   
    /**
     * Method for sender to transmit data
     * @param fis
     * @param socket
     * @throws IOException
     */
    private void sendData(FileInputStream fis, DatagramSocket socket) throws IOException{

        //Sliding window variables. Segments NOT bytes
        int base = 0; // oldest unack seqNum
        int nextSeqNum = 0;
        
        Map<Integer, TCPPacket> window = new HashMap<>(); // unack packets
        Timer retransmissionTimer; 
        
        byte[] fileBytes = fis.readAllBytes();
        int fileLength = fileBytes.length;
        //int dataPtr = 0;

        
        while (nextSeqNum < base + (sws * mtu) && nextSeqNum < fileLength) {


            //send all packets in window as long as there are bytes left to send
            int startIndex = nextSeqNum;
            int endIndex = Math.min(startIndex + mtu, fileLength);
            
            byte[] sendBuffer = Arrays.copyOfRange(fileBytes, startIndex, endIndex);

            TCPPacket packet = new TCPPacket(nextSeqNum, ackNum, false, true, false, sendBuffer, System.nanoTime());

            sendPacket(packet, socket, remoteIP);

            window.put(nextSeqNum, packet);

            //TODO retransmission timer
            nextSeqNum += sendBuffer.length;
        }
    }

    /**
     * Method for receiver to receive data
     * 
     * @param socket
     * @param receiverSeqNum
     * @param fos
     * @throws IOException
     */
    private void receiveData(DatagramSocket socket, int receiverSeqNum, FileOutputStream fos) throws IOException{
        
        int expectedSeqNum = 1;

        //Stores out of order packets. seqNum as key
        Map<Integer, TCPPacket> receivedBuffer = new TreeMap<>();

        while (state == TcpStates.ESTABLISHED || state == TcpStates.CLOSE_WAIT){

            TCPPacket recPacket = receivePacket(socket);

            //drop if checksum failure
            if (recPacket == null){
                continue;
            }

            //if FYN flag
            if (recPacket.getFinFlag()){

                state = TcpStates.CLOSE_WAIT;

                 //flush buffer only. no more packet receiving
                 while (receivedBuffer.containsKey(expectedSeqNum)){
                    TCPPacket nexTcpPacket = receivedBuffer.remove(expectedSeqNum);

                    byte[] dataBuffer = nexTcpPacket.getPayload();

                    fos.write(dataBuffer);

                    expectedSeqNum = expectedSeqNum + dataBuffer.length;

                    handleCloseReceiver(socket, recPacket);
                    continue;
                }
            }

            int recSeqNum = recPacket.getSeqNum();
            byte[] dataBuffer = recPacket.getPayload();

            if (recSeqNum == expectedSeqNum){
                fos.write(dataBuffer);

                //updated excpected seqNum
                expectedSeqNum = expectedSeqNum + dataBuffer.length;

                //flush buffer
                while (receivedBuffer.containsKey(expectedSeqNum)){
                    TCPPacket nexTcpPacket = receivedBuffer.remove(expectedSeqNum);

                    //update for ack
                    recSeqNum = nexTcpPacket.getSeqNum();
                    dataBuffer = nexTcpPacket.getPayload();

                    fos.write(dataBuffer);

                    expectedSeqNum = expectedSeqNum + dataBuffer.length;
                }
            }

            //buffer if out of order
            else if (recSeqNum > expectedSeqNum ){
                receivedBuffer.put(recSeqNum, recPacket);
            }

            //send ACK
            TCPPacket ackPack = new TCPPacket(receiverSeqNum, recSeqNum + dataBuffer.length, false, true, false, null, System.nanoTime());
            
            receiverSeqNum++;

            sendPacket(ackPack, socket, remoteIP);
        }
    }
    /**
     * Prints packet information upon send/receive
     * 
     * @param mode
     * @param packet
     * @return
     */
    public String toString(String mode, TCPPacket packet) {

        String sndRec = "";
        
        if (mode.equals("send")){
            sndRec = "snd";
        }

        if (mode.equals("receive")){
            sndRec = "rcv";
        }

        String output = sndRec + " " + System.nanoTime() + " " + packet.toString() + " payload length: " + packet.getPayloadLength();

        return output;
    }

    /**
     * Method for close initiator to begin closing
     * 
     * @param socket
     * @param state
     * @throws IOException
     */
    private void handleCloseInitiator(DatagramSocket socket, TcpStates state) throws IOException {

        // send FIN, wait for ACK, etc.
        //1. send fIN
        TCPPacket packet = new TCPPacket(seqNum, ackNum, false, false, true, null, System.nanoTime());
        seqNum++;
        
        sendPacket(packet, socket, remoteIP);
        state = TcpStates.FIN_WAIT_1;

        //2. receive ack for FIN
        TCPPacket recPacket = receivePacket(socket);
        ackNum = recPacket.getSeqNum() + 1;
        
        //Case 1: Receive Ack for FIN. No FIN Flag
        if (recPacket.getAckFlag() && !recPacket.getFinFlag() && recPacket.getPayloadLength() == 0){
            //wait for FIN
            recPacket = receivePacket(socket);
            ackNum = recPacket.getSeqNum() + 1;

            if (recPacket.getFinFlag()){

                //send ack for fin flag
                packet = new TCPPacket(seqNum, ackNum, false, true, true, null, recPacket.getTimeStamp());

                sendPacket(packet, socket, remoteIP);
                seqNum++;

                state = TcpStates.FIN_WAIT_2;
            }

        }
        //Case 2: Ack for FIN and FIN flag
        else if (recPacket.getAckFlag() && recPacket.getFinFlag() && recPacket.getPayloadLength() == 0){
            
            packet = new TCPPacket(seqNum, ackNum, false, true, true, null, recPacket.getTimeStamp());

            sendPacket(packet, socket, remoteIP);
            seqNum++;
            
            state = TcpStates.FIN_WAIT_2;
        }
        
        //3. TODO time out initiator 


    }
    
    /**
     * Method for close receiver to begin closin
     * 
     * @param socket
     * @param finPacket
     * @throws IOException
     */
    private void handleCloseReceiver(DatagramSocket socket, TCPPacket finPacket) throws IOException {
        
        // FIN packet has already been received
        // send ACK, then send own FIN, wait for ACK, etc.
        ackNum = finPacket.getSeqNum() + 1;
        TCPPacket packet = new TCPPacket(seqNum, ackNum, false, true, true, null, finPacket.getTimeStamp());
        seqNum++;

        //wait for ACK
        TCPPacket recPacket = receivePacket(socket);

        if (recPacket.getAckFlag() && !recPacket.getFinFlag() && recPacket.getPayloadLength() ==0){
            state = TcpStates.CLOSED;
        }
    }

    
    /**
     * Helper method for sending ACKs
     * @param packet - for extracting sender's host information
     * @param tPacket -for extracting TCP packet information
          * @throws IOException 
          */
          /**
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
    
            TCPPacket tcpAckPacket = new TCPPacket(seqNum, ackNum, synFlag, ackFlag, finFlag, null);
    
            DatagramPacket sendPacket = new DatagramPacket(tcpAckPacket.serialize(), tcpAckPacket.getOverallLength(), senderAddress, senderPort);
    
            socket.send(sendPacket);
    
        }
     */
}
