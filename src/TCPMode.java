import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
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
    int timeoutTime = 5000; // milliseconds

    TcpStates state = TcpStates.CLOSED; // initialize with closed

    enum TcpStates {
        // sender and sender+receiver states
        CLOSED, // No connection
        SYN_SENT, // Sent SYN, waiting for SYN-ACK
        ESTABLISHED, // Connection open
        FIN_WAIT_1, // Sent FIN, waiting for ACK
        FIN_WAIT_2, // Got ACK, waiting for receiver's FIN
        TIME_WAIT, // Sent final ACK, waiting to ensure receiver received

        // receiver states
        LISTEN, // Waiting for an incoming SYN
        SYN_RECEIVED, // Received SYN, sent SYN-ACK, waiting for ACK
        CLOSE_WAIT, // Received FIN frxom sender, waiting to send own FIN
        LAST_ACK, // Sent own FIN, waiting for final ACK
    }

    /**
     * Constructor for sender
     * 
     * @param mode
     * @param port
     * @param remoteIP
     * @param remotePort
     * @param fileName
     * @param mtu
     * @param sws
     */
    public TCPMode(String port, String remoteIP, String remotePort, String fileName, String mtu, String sws) {
        this.mode = SEND_MODE;
        this.port = port;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
        this.fileName = fileName;
        this.mtu = Integer.parseInt(mtu);
        this.sws = Integer.parseInt(sws);
    }

    /**
     * Receiver Mode
     * 
     * @param port
     * @param mtu
     * @param sws
     * @param fileName
     */
    public TCPMode(String port, String mtu, String sws, String fileName) {
        this.mode = REC_MODE;
        this.port = port;
        this.mtu = Integer.parseInt(mtu);
        this.sws = Integer.parseInt(sws);
        this.fileName = fileName;
    }

    public void run() {

        // sender logic
        if (mode.equals(SEND_MODE)) {

            // set initial state
            System.out.println("Sender initialization");

            try {
                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));

                FileInputStream fis = new FileInputStream(fileName);

                byte[] buffer = new byte[mtu];
                int bytesRead;

                // initiation - 3 way hand shake
                // CLOSED to SYN_ACK
                while (state == TcpStates.CLOSED || state == TcpStates.SYN_SENT) {

                    TCPPacket ackPacket = null;
                    // step 1
                    if (state == TcpStates.CLOSED) {
                        TCPPacket tPacket = new TCPPacket(seqNum, ackNum, true, false, false, null, System.nanoTime());

                        //sequence and ack verification handled within sendWithRetries method
                        ackPacket = sendWithRetries(tPacket, socket, remoteIP);

                        if (ackPacket != null && ackPacket.getSynFlag() && ackPacket.getAckFlag() && ackPacket.getAckNum() == seqNum + 1){
                            state = TcpStates.ESTABLISHED;
                            System.out.println("sender state " + state);
                            ackNum = ackPacket.getSeqNum() + 1;
                            seqNum++;                        
                        }
                        else{
                            state = TcpStates.SYN_SENT;
                        }
                    }

                    // step 3, send ACK
                    if (state == TcpStates.ESTABLISHED) {
                        System.out.println("sender phase established");

                        boolean finalAckSent = false;

                        while (!finalAckSent) {
                            TCPPacket tPacket = new TCPPacket(seqNum, ackPacket.getSeqNum() + 1, false, true, false,
                                        null, System.nanoTime());
                            sendPacket(tPacket, socket, remoteIP);
                        
                            // Wait briefly to see if SYN-ACK gets repeated
                            try {
                                socket.setSoTimeout(timeoutTime);
                                TCPPacket maybeRepeat = receivePacket(socket); //receive only if packet repeated
                        
                                if (maybeRepeat.getSynFlag() && maybeRepeat.getAckFlag() &&
                                    maybeRepeat.getAckNum() == seqNum) {
                                    // SYN-ACK was repeated — resend final ACK
                                    continue;
                                } else {
                                    // Connection is ready - break out
                                    finalAckSent = true;
                                }
                        
                            } catch (SocketTimeoutException e) {
                                // If timeout, ACK received
                                finalAckSent = true;
                            }
                        }
                    }
                }

                sendData(fis, socket);

                handleCloseInitiator(socket, state);

                // close and clean
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

        // Receiver mode
        if (mode.equals(REC_MODE)) {

            state = TcpStates.LISTEN;

            try {
                System.out.println("Receiver on port: " + port);

                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));

                // handshake initialization
                while (state == TcpStates.LISTEN || state == TcpStates.SYN_RECEIVED) {

                    TCPPacket recPacket = receivePacket(socket);

                    //handle SYN
                    if (recPacket.getSynFlag() == true && recPacket.getAckFlag() == false && recPacket.getSeqNum() == 0
                            && recPacket.getAckNum() == 0) {

                        //System.out.println("state -> SYN_RECEIVED");
                        //update state after SYN receipt
                        state = TcpStates.SYN_RECEIVED;
                        
                        //send SYN-ACK in response with retries
                        TCPPacket sendTcpPacket = new TCPPacket(seqNum, recPacket.getSeqNum() + 1, true, true, false,
                                null, recPacket.getTimeStamp());
                        seqNum++;

                        recPacket = sendWithRetries(sendTcpPacket, socket, remoteIP);
                    }

                    // Watch for SYN retries
                    if (state == TcpStates.SYN_RECEIVED && recPacket.getSynFlag() && !recPacket.getAckFlag()) {
                        // The sender is retransmitting the SYN, so resend SYN-ACK
                        TCPPacket resendTcpPacket = new TCPPacket(seqNum, recPacket.getSeqNum() + 1, true, true, false,
                            null, recPacket.getTimeStamp());
                        seqNum++;

                    // Send SYN-ACK again without retries (just a single send)
                    sendPacket(resendTcpPacket, socket, remoteIP);
                    }

                    // move from SYN_RECEIVED TO ESTABLISHED
                    if (recPacket.getSynFlag() == false && recPacket.getAckFlag() == true
                            && recPacket.getPayloadLength() == 0) {
                        
                        state = TcpStates.ESTABLISHED;
                        ackNum++;
                    }
                    //System.out.println(state);
                }

                // Established phase - receive data
                FileOutputStream fos = new FileOutputStream(fileName);

                System.out.println("receiver ackNum: " + ackNum);

                receiveData(socket, fos);


                //TODO clean this up
                
                TCPPacket finPacket = receivePacket(socket);
                handleCloseReceiver(socket, finPacket);

                // state is CLOSED
                fos.close();
                socket.close();

            } catch (IOException e) {
                System.out.println("Error listening on port: " + e);
            } finally {

                System.exit(0);
            }

        }
    }

    /**
     * Helper method for sending packets
     * 
     * @param payload
     * @param socket
     * @param remoteIP
     * @throws IOException
     */
    private void sendPacket(TCPPacket payload, DatagramSocket socket, String remoteIP) throws IOException {

        byte[] serialized = payload.serialize();
        // System.out.println("Serialized packet length: " + serialized.length);
        DatagramPacket packet = new DatagramPacket(serialized, 0, serialized.length, InetAddress.getByName(remoteIP),
                Integer.parseInt(remotePort));

        System.out.println(toString("send", payload));
        socket.send(packet);

    }

    /**
     * Helper method for receiving packets
     * 
     * @param buffer
     * @param socket
     * @return Datagram packet for further processing
     * @throws IOException
     */
    private TCPPacket receivePacket(DatagramSocket socket) throws IOException {

        byte[] datagramBuffer = new byte[1500];

        DatagramPacket packet = new DatagramPacket(datagramBuffer, datagramBuffer.length);

        socket.receive(packet);

        this.remoteIP = packet.getAddress().getHostAddress().toString();// set sender IP address
        this.remotePort = String.valueOf(packet.getPort());

        byte[] receivedData = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

        TCPPacket tPacket = new TCPPacket(receivedData).deserialize();

        // if checksum failed, return null
        if (tPacket == null) {
            return null;
        }

        System.out.println(this.toString("receive", tPacket));

        return tPacket;
    }

    /**
     * Method to send SYN and FIN packet and validate ACKS
     * 
     * @param socket
     * @param packetToSend
     * @param timeoutMillis
     * @return
     * @throws IOException
     */
    public TCPPacket sendWithRetries(TCPPacket payload, DatagramSocket socket, String remoteIP) throws IOException {
        byte[] buffer = new byte[1500]; // adjust size as needed
        DatagramPacket ackPacket = new DatagramPacket(buffer, buffer.length);
        socket.setSoTimeout(timeoutTime);

        //sending parameters
        byte[] serialized = payload.serialize();
        DatagramPacket packetToSend = new DatagramPacket(serialized, 0, serialized.length, InetAddress.getByName(remoteIP),
                Integer.parseInt(remotePort));

        for (int attempt = 0; attempt < 16; attempt++) {
            // Send packet
            socket.send(packetToSend);
            System.out.println(toString("send", payload));

            try {
                // Wait for ACK
                socket.receive(ackPacket);
                
                this.remoteIP = ackPacket.getAddress().getHostAddress().toString();// set sender IP address
                this.remotePort = String.valueOf(ackPacket.getPort());
                byte[] receivedData = Arrays.copyOfRange(ackPacket.getData(), 0, ackPacket.getLength()); 
                TCPPacket tPacket = new TCPPacket(receivedData).deserialize();
                System.out.println(this.toString("receive", tPacket));

                if (tPacket.getAckFlag() && tPacket.getAckNum() == payload.getSeqNum() + 1) {
                    return tPacket;
                } 


            } catch (SocketTimeoutException e) {
                continue;
            }
        }
        System.out.println("16 transmission attempts were made. The packet could not be sent");
        return null;
    }

    /**
     * Method for sender to transmit data
     * 
     * @param fis
     * @param socket
     * @throws IOException
     */
    private void sendData(FileInputStream fis, DatagramSocket socket) throws IOException {

        // Sliding window variables. Segments NOT bytes
        int base = 1; // oldest unack seqNum

        Map<Integer, TCPPacket> window = new HashMap<>(); // unack packets
        Map<Integer, Timer> timers = new HashMap<>(); // timeout timers

        byte[] fileBytes = fis.readAllBytes();
        int fileLength = fileBytes.length;
        // int dataPtr = 0;

        while (base < fileLength) {

            while (seqNum < base + (sws * mtu) && seqNum < fileLength) {

                // send all packets in window as long as there are bytes left to send
                int startIndex = seqNum;
                int endIndex = Math.min(startIndex + mtu, fileLength);

                byte[] sendBuffer = Arrays.copyOfRange(fileBytes, startIndex, endIndex);

                TCPPacket packet = new TCPPacket(seqNum, ackNum, false, true, false, sendBuffer, System.nanoTime());

                System.out.println("sender send data");
                sendPacket(packet, socket, remoteIP);

                window.put(seqNum, packet);

                // Start timer for this packet
                int currentSeq = seqNum;
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            if (window.containsKey(currentSeq)) {
                                System.out.println(
                                        "Timeout for packet with seqNum: " + currentSeq + ", retransmitting...");
                                sendPacket(window.get(currentSeq), socket, remoteIP);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }, timeoutTime);
                timers.put(seqNum, timer);

                seqNum += sendBuffer.length;
            }

            // receive ACK
            boolean ackReceived = false;

            while (!ackReceived) {
                try {
                    socket.setSoTimeout(timeoutTime);

                    TCPPacket recPacket = receivePacket(socket);

                    if (recPacket != null && recPacket.getAckFlag()) {
                        int ackSeqNum = recPacket.getAckNum();

                        if (ackSeqNum >= base) {

                            window.entrySet().removeIf(entry -> entry.getKey() <= ackSeqNum);

                            timers.entrySet().removeIf(entry -> entry.getKey() <= ackSeqNum);

                            // slide base. if windows is empty, set high value that will be outside of range
                            base = window.isEmpty() ? Integer.MAX_VALUE : Collections.min(window.keySet());

                            ackReceived = true;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout waiting for ACK, retrying...");
                    // Retransmit all unacknowledged packets (those still in the window)

                }
            }
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
    private void receiveData(DatagramSocket socket, FileOutputStream fos) throws IOException {

        int expectedSeqNum = ackNum;
        int receivedPacketsCount = 0;

        // Stores out of order packets. seqNum as key
        Map<Integer, TCPPacket> receivedBuffer = new TreeMap<>();

        long lastPacketTime = System.nanoTime();  // Time of the last received packet

        while (state == TcpStates.ESTABLISHED || state == TcpStates.CLOSE_WAIT) {
            System.out.println("receiving data");

            try {
                //set timeout
                socket.setSoTimeout((int) timeoutTime);  // Units = milliseconds. TODO verify

                TCPPacket recPacket = receivePacket(socket);

                // drop if checksum failure
                if (recPacket == null) {
                    System.out.println("checksum failure");
                    continue;
                }

                //reset last packet time
                else {
                    lastPacketTime = System.nanoTime();  // Reset the last packet time
                }
    

                receivedPacketsCount++;

                // if FIN flag
                if (recPacket.getFinFlag()) {
                    System.out.println("receiver FIN flag detected");

                    state = TcpStates.CLOSE_WAIT;

                    // flush buffer only. no more packet receiving
                    while (receivedBuffer.containsKey(expectedSeqNum)) {
                        TCPPacket nexTcpPacket = receivedBuffer.remove(expectedSeqNum);

                        byte[] dataBuffer = nexTcpPacket.getPayload();

                        fos.write(dataBuffer);

                        expectedSeqNum += dataBuffer.length;

                        handleCloseReceiver(socket, recPacket);
                        continue;
                    }
                }

                int recSeqNum = recPacket.getSeqNum();
                byte[] dataBuffer = recPacket.getPayload();

                if (recSeqNum == expectedSeqNum) {

                    fos.write(dataBuffer);

                    // updated excpected seqNum
                    expectedSeqNum += dataBuffer.length;

                    //remove received packet if already in buffer
                    receivedBuffer.remove(recSeqNum);

                    // flush buffer
                    while (receivedBuffer.containsKey(expectedSeqNum)) {
                        TCPPacket nexTcpPacket = receivedBuffer.remove(expectedSeqNum);

                        // update for ack
                        recSeqNum = nexTcpPacket.getSeqNum();
                        dataBuffer = nexTcpPacket.getPayload();

                        ackNum = recSeqNum + dataBuffer.length;

                        fos.write(dataBuffer);
                        receivedPacketsCount++;

                        expectedSeqNum = expectedSeqNum + dataBuffer.length;
                    }
                }

                // buffer if out of order
                else if (recSeqNum > expectedSeqNum) {
                    System.err.println("buffering out of order packeta");
                    receivedBuffer.put(recSeqNum, recPacket);
                }

                // send ACK
                if (receivedPacketsCount >= sws) {
                    // Send an ACK for the highest sequence number processed so far
                    
                    TCPPacket ackPack = new TCPPacket(seqNum, expectedSeqNum , false, true, false, null,
                            System.nanoTime());
                    sendPacket(ackPack, socket, remoteIP);

                    // Reset received packets count for the next window
                    receivedPacketsCount = 0;
                }
            } catch (SocketTimeoutException e) {
                // Handle the timeout exception if it occurs
                System.out.println("Timeout while waiting for packet, sending ACK for last processed packet");

                // Send an ACK for the last processed sequence number
                TCPPacket timeoutAck = new TCPPacket(seqNum, expectedSeqNum, false, true, false, null,
                        System.nanoTime());
                sendPacket(timeoutAck, socket, remoteIP);
            }

        }
        // After exiting the loop, if we've processed any remaining packets, send the
        // final ACK
        if (receivedPacketsCount > 0) {
            TCPPacket finalAck = new TCPPacket(seqNum, expectedSeqNum, false, true, false, null, System.nanoTime());
            sendPacket(finalAck, socket, remoteIP);
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

        if (mode.equals("send")) {
            sndRec = "snd";
        }

        if (mode.equals("receive")) {
            sndRec = "rcv";
        }
        String output = sndRec + " " + System.nanoTime() + " " + packet.toString();

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
        // 1. send fIN
        TCPPacket packet = new TCPPacket(seqNum, ackNum, false, false, true, null, System.nanoTime());
        seqNum++;

        sendPacket(packet, socket, remoteIP);
        state = TcpStates.FIN_WAIT_1;

        // 2. receive ack for FIN
        TCPPacket recPacket = receivePacket(socket);
        ackNum = recPacket.getSeqNum() + 1;

        // Case 1: Receive Ack for FIN. No FIN Flag
        if (recPacket.getAckFlag() && !recPacket.getFinFlag() && recPacket.getPayloadLength() == 0) {
            // wait for FIN
            recPacket = receivePacket(socket);
            ackNum = recPacket.getSeqNum() + 1;

            if (recPacket.getFinFlag()) {

                // send ack for fin flag
                packet = new TCPPacket(seqNum, ackNum, false, true, true, null, recPacket.getTimeStamp());

                sendPacket(packet, socket, remoteIP);
                seqNum++;

                state = TcpStates.FIN_WAIT_2;
            }

        }
        // Case 2: Ack for FIN and FIN flag
        else if (recPacket.getAckFlag() && recPacket.getFinFlag() && recPacket.getPayloadLength() == 0) {

            packet = new TCPPacket(seqNum, ackNum, false, true, true, null, recPacket.getTimeStamp());

            sendPacket(packet, socket, remoteIP);
            seqNum++;

            state = TcpStates.FIN_WAIT_2;
        }

        // 3. TODO time out initiator

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

        // wait for ACK
        TCPPacket recPacket = receivePacket(socket);

        if (recPacket.getAckFlag() && !recPacket.getFinFlag() && recPacket.getPayloadLength() == 0) {
            state = TcpStates.CLOSED;
        }
    }

    /**
     * Helper method for sending ACKs
     * 
     * @param packet  - for extracting sender's host information
     * @param tPacket -for extracting TCP packet information
     * @throws IOException
     */
    /**
     * private void sendAck(DatagramPacket packet, DatagramSocket socket, TCPPacket
     * tPacket, TcpStates state) throws IOException{
     * //destination information
     * InetAddress senderAddress = packet.getAddress();
     * int senderPort = packet.getPort();
     * 
     * //TCP Packet fields
     * int seqNum;
     * int ackNum;
     * boolean synFlag = false;
     * boolean ackFlag = true;
     * boolean finFlag = false;
     * 
     * if ((state == TcpStates.CLOSED)){
     * ackNum = 0;
     * }
     * else{
     * ackNum = tPacket.getSeqNum() + 1;
     * }
     * 
     * //seqNum 0 for initation and
     * if (state == TcpStates.CLOSED || state == TcpStates.SYN_SENT){
     * seqNum = 0;
     * synFlag = true;
     * 
     * }
     * else{
     * seqNum = tPacket.getSeqNum();
     * }
     * 
     * TCPPacket tcpAckPacket = new TCPPacket(seqNum, ackNum, synFlag, ackFlag,
     * finFlag, null);
     * 
     * DatagramPacket sendPacket = new DatagramPacket(tcpAckPacket.serialize(),
     * tcpAckPacket.getOverallLength(), senderAddress, senderPort);
     * 
     * socket.send(sendPacket);
     * 
     * }
     */
}
