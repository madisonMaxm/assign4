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
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    TcpStates state = TcpStates.CLOSED; // initialize with closed

    Long startTime = (long) 0; //for calculating all packet times relative to this

    TimeoutCalc timeoutCalc = new TimeoutCalc();

    //schedule for timers
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);  
    private Map<Integer, ScheduledFuture<?>> scheduledTasks = new HashMap<>();

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

    // Stats summary information
    long totalBytes =  0; //data transferred OR received
    int packetsSent = 0;
    int packetsReceived = 0;
    int outOfOrderPacketsDiscarded = 0;
    int checksumDrops = 0;
    int retransmissions = 0;
    int dupAcks = 0;

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
            //System.out.println("Sender initialization");
            try {
                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));

                FileInputStream fis = new FileInputStream(fileName);

                // initiation - 3 way hand shake
                // CLOSED to SYN_ACK
                while (state == TcpStates.CLOSED || state == TcpStates.SYN_SENT) {

                    TCPPacket ackPacket = null;
                    // step 1
                    if (state == TcpStates.CLOSED) {

                        startTime = System.nanoTime(); // program start time from which all other times are relative

                        TCPPacket tPacket = new TCPPacket(seqNum, ackNum, true, false, false, null, startTime);

                        //sequence and ack verification handled within sendWithRetries method
                        ackPacket = sendWithRetries(tPacket, socket, remoteIP);

                        if (ackPacket != null && ackPacket.getSynFlag() && ackPacket.getAckFlag() && ackPacket.getAckNum() == seqNum + 1){
                            state = TcpStates.ESTABLISHED;
                            //System.out.println("sender state " + state);
                            ackNum = ackPacket.getSeqNum() + 1;
                            seqNum++;                        
                        }
                        else{
                            state = TcpStates.SYN_SENT;
                        }
                    }

                    // step 3, send ACK
                    if (state == TcpStates.ESTABLISHED) {
                        //System.out.println("sender phase established");
                        // dropped ack handled by receiver's receivedData method
                        TCPPacket tPacket = new TCPPacket(seqNum, ackPacket.getSeqNum() + 1, false, true, false,
                                    null, System.nanoTime());
                        sendPacket(tPacket, socket, remoteIP);
                        
                    }
                }

                //send all data
                sendData(fis, socket);

                //initiate close
                handleCloseInitiator(socket, state);

                // close and clean
                fis.close();
                socket.close();
                scheduler.shutdown();

            } catch (UnknownHostException e) {
                e.printStackTrace();
                System.exit(1);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
            finally{
                System.out.println("=== Transfer Statistics ===");
                System.out.println("Data transferred: " + totalBytes + " bytes");
                System.out.println("Packets sent: " + packetsSent);
                System.out.println("Packets received: " + packetsReceived);
                System.out.println("Out-of-order packets discarded: " + outOfOrderPacketsDiscarded);
                System.out.println("Packets discarded due to checksum: " + checksumDrops);
                System.out.println("Retransmissions: " + retransmissions);
                System.out.println("Duplicate ACKs: " + dupAcks);
                System.exit(0);
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

                        //set receiver initial time
                        startTime = recPacket.getTimeStamp();
                        
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

                    // Send SYN-ACK again without retries 
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

                receiveData(socket, fos);
                
                if (state != TcpStates.CLOSED) {
                    TCPPacket finPacket = receivePacket(socket);
                    handleCloseReceiver(socket, finPacket);
                }

                // state is CLOSED
                fos.close();
                socket.close();

            } catch (IOException e) {
                System.out.println("Error listening on port: " + e);
            } finally {
                System.out.println("=== Transfer Statistics ===");
                System.out.println("Data transferred: " + totalBytes + " bytes");
                System.out.println("Packets sent: " + packetsSent);
                System.out.println("Packets received: " + packetsReceived);
                System.out.println("Out-of-order packets discarded: " + outOfOrderPacketsDiscarded);
                System.out.println("Packets discarded due to checksum: " + checksumDrops);
                System.out.println("Retransmissions: " + retransmissions);
                System.out.println("Duplicate ACKs: " + dupAcks);
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

        totalBytes += payload.getPayloadLength(); //update date sent

        byte[] serialized = payload.serialize();
        // System.out.println("Serialized packet length: " + serialized.length);
        DatagramPacket packet = new DatagramPacket(serialized, 0, serialized.length, InetAddress.getByName(remoteIP),
                Integer.parseInt(remotePort));

        System.out.println(toString("send", payload));
        socket.send(packet);
        packetsSent++;

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
        packetsReceived++;

        this.remoteIP = packet.getAddress().getHostAddress().toString();// set sender IP address
        this.remotePort = String.valueOf(packet.getPort());

        byte[] receivedData = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

        TCPPacket tPacket = new TCPPacket(receivedData).deserialize();
        // if checksum failed, return null
        if (tPacket == null) {
            checksumDrops++;
            return null;
        }

        totalBytes += tPacket.getPayloadLength();//update data received

        //set start time from first received packet
        if (startTime == 0){
            startTime = tPacket.getTimeStamp();
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
        
        TCPPacket ackPacket = null;
        socket.setSoTimeout(timeoutCalc.getTimeOut());

        //sending parameters
        byte[] serialized = payload.serialize();
        DatagramPacket packetToSend = new DatagramPacket(serialized, 0, serialized.length, InetAddress.getByName(remoteIP),
                Integer.parseInt(remotePort));

        for (int attempt = 0; attempt < 16; attempt++) {
            // Send packet
            socket.send(packetToSend);
            if(attempt > 0){
                retransmissions++;
            }

            packetsSent++;
            System.out.println(toString("send", payload));

            try {
                // Wait for ACK
                ackPacket = receivePacket(socket).deserialize();

                //update timer only on first transmission
                if (attempt == 0 && ackPacket != null){
                    long currentTime=System.nanoTime();
                    long packetTimeStamp = ackPacket.getTimeStamp();
                    long sampleRTT = currentTime - packetTimeStamp;

                    timeoutCalc.updateRTT(sampleRTT);
                }
                        
                //determine if this can be removed
                if(ackPacket.getAckNum() < payload.getSeqNum() +1){
                    dupAcks++;
                    continue;
                }

                if (ackPacket.getAckFlag() && ackPacket.getAckNum() == payload.getSeqNum() + 1) {
                    totalBytes += ackPacket.getPayloadLength(); // only log data that was succesfully received
                    return ackPacket;
                } 


            } catch (SocketTimeoutException e) {
                continue;
            }
        }
        System.out.println("16 transmission attempts were made. The packet could not be sent");
        System.exit(1);
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

        int base = 1; // oldest unack seqNum

        Map<Integer, TCPPacket> window = new HashMap<>(); // unack packets

        byte[] fileBytes = fis.readAllBytes();
        int fileLength = fileBytes.length;

         //initialize for fast retransmit and duplicate ACK tracking
         int lastAckSent = -1;
         int dupAckCount = 0;
 

        while (base < fileLength) {

            while (seqNum < base + (sws * mtu) && seqNum <= fileLength) {


                // send all packets in window as long as there are bytes left to send
                int startIndex = seqNum -1; //compensate for indexing
                int endIndex = Math.min(startIndex + mtu, fileLength);

                byte[] sendBuffer = Arrays.copyOfRange(fileBytes, startIndex, endIndex);

                TCPPacket packet = new TCPPacket(seqNum, ackNum, false, true, false, sendBuffer, System.nanoTime());

                //System.out.println("Sending packet with seqNum: " + seqNum + " (Base: " + base + "), Data length: " + sendBuffer.length);

                sendPacket(packet, socket, remoteIP);

                window.put(seqNum, packet);

                // Start timer for this packet
                int currentSeq = seqNum;
                ScheduledFuture<?> future = scheduler.schedule(() -> {
                try {
                    if (window.containsKey(currentSeq)) {
                        retransmissions++;
                        sendPacket(window.get(currentSeq), socket, remoteIP);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }, timeoutCalc.getTimeOut(), TimeUnit.MILLISECONDS);

            // Store the future task to cancel it later if necessary
            scheduledTasks.put(seqNum, future);

                seqNum += sendBuffer.length;
            }

            // receive ACK
            boolean ackReceived = false;

            while (!ackReceived) {
                try {
                    socket.setSoTimeout(timeoutCalc.getTimeOut());

                    TCPPacket recPacket = receivePacket(socket);

                    if (recPacket != null && recPacket.getAckFlag()) {

                        //Catch edge case of dropped ACK packet
                        if (recPacket.getSynFlag()){
                            //resend ack
                            TCPPacket tPacket = new TCPPacket(1, 1, false, true, false,
                            null, System.nanoTime());
                            sendPacket(tPacket, socket, remoteIP);

                            dupAcks++;
                            ackReceived = true;
                            continue;
                        }

                        int ackSeqNum = recPacket.getAckNum();

                        //if duplicate ACK
                        if  (ackSeqNum == lastAckSent){
                            dupAckCount++;
                            dupAcks++;

                            //Fast restransmit
                            if (dupAckCount >= 3){

                                System.out.println("retransmit, 3 acks detected");

                                TCPPacket resendPacket = null;
                                for (Map.Entry<Integer, TCPPacket> entry : window.entrySet()) {
                                    int packetStart = entry.getKey();
                                    int packetEnd = packetStart + entry.getValue().getPayloadLength();
                                    if (packetEnd == ackSeqNum) {
                                        resendPacket = entry.getValue();
                                        break;
                                    }
}
                                if (resendPacket != null){
                                    retransmissions++;
                                    sendPacket(resendPacket, socket, remoteIP);
                                }
                            }
                        } else if (ackSeqNum > lastAckSent){
                            lastAckSent = ackSeqNum;
                            dupAckCount = 0;

                            //update timer only on new packet ack
                            long currentTime = System.nanoTime();
                            long ackTimeStamp = recPacket.getTimeStamp();
                            long sampleRTT = currentTime - ackTimeStamp;
                            timeoutCalc.updateRTT(sampleRTT);
                        }


                        if (ackSeqNum >= base) {

                            window.entrySet().removeIf(entry -> entry.getKey() <= ackSeqNum);

                            scheduledTasks.entrySet().removeIf(entry -> entry.getKey() <= ackSeqNum);

                            // slide base. if windows is empty, set high value that will be outside of range
                            if (!window.isEmpty()) {
                                base = Collections.min(window.keySet());  
                            } else {
                               
                                if (seqNum < fileLength) {
                                    base = seqNum;
                                } else {
                                    base = Integer.MAX_VALUE;  
                                }
                            }
                            //System.out.println("Window slide: new base = " + base);  
                            ackReceived = true;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    //System.out.println("Timeout waiting for ACK, retrying...");
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

        //variables to handle close
        boolean finReceived = false;
        TCPPacket finPacket = null;
        // Stores out of order packets. seqNum as key
        Map<Integer, TCPPacket> receivedBuffer = new TreeMap<>();

        long lastPacketTime = System.nanoTime();  // Time of the last received packet

        while (state == TcpStates.ESTABLISHED) {
            //System.out.println("receiving data");

            try {
                //set timeout
                socket.setSoTimeout((int) timeoutCalc.getTimeOut()); 
                TCPPacket recPacket = receivePacket(socket);

                // drop if checksum failure
                if (recPacket == null) {
                    //System.out.println("checksum failure");
                    continue;
                }

                //reset last packet time
                else {
                    lastPacketTime = System.nanoTime();  // Reset the last packet time
                }
    
                receivedPacketsCount++;


                // if FIN flag
                if (recPacket.getFinFlag()) {
                    // System.out.println("receiver FIN flag detected");
                    finReceived = true;
                    finPacket = recPacket;
                    state = TcpStates.CLOSE_WAIT;
                }

                int recSeqNum = recPacket.getSeqNum();
                byte[] dataBuffer = recPacket.getPayload();

                //System.out.println("Expected SeqNum: " + expectedSeqNum + ", Received SeqNum: " + recSeqNum);
                //System.out.println("Sliding window range: " + expectedSeqNum + " to " + (expectedSeqNum + sws - 1));

                if (recSeqNum < expectedSeqNum || recSeqNum >=expectedSeqNum + sws){
                    //System.out.println("Discarding packet (out of order or outside window): SeqNum " + recSeqNum);

                    outOfOrderPacketsDiscarded++;
                    continue;//discard packet outside of window
                }

                //case 1: in order packet
                if (recSeqNum == expectedSeqNum) {
                    //System.out.println("Writing data to file: SeqNum " + recSeqNum);
                    fos.write(dataBuffer);

                    // updated excpected seqNum
                    expectedSeqNum += dataBuffer.length;
                    //System.out.println("Expected SeqNum updated to: " + expectedSeqNum);

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

                // case 2: buffer if out of order
                else if (recSeqNum > expectedSeqNum) {
                    System.err.println("buffering out of order packeta");
                    receivedBuffer.put(recSeqNum, recPacket);
                }

                // case 3: send ACK
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
                //System.out.println("Timeout while waiting for packet, sending ACK for last processed packet");
                dupAcks++;

                // Send an ACK for the last processed sequence number
                TCPPacket timeoutAck = new TCPPacket(seqNum, expectedSeqNum, false, true, false, null,
                        System.nanoTime());
                sendPacket(timeoutAck, socket, remoteIP);
            }

            if (finReceived && finPacket != null) {
                // Flush remaining in-order buffered packets
                while (receivedBuffer.containsKey(expectedSeqNum)) {
                    TCPPacket nextTcpPacket = receivedBuffer.remove(expectedSeqNum);
                    byte[] dataBuffer = nextTcpPacket.getPayload();
                    fos.write(dataBuffer);
                    expectedSeqNum += dataBuffer.length;
                }
            
                // Send final ACK/FIN for closure
                handleCloseReceiver(socket, finPacket);
            }

        }
        // After exiting the loop, send final ack if more packets were processed
        if (receivedPacketsCount > 0 && state != TcpStates.CLOSED) {
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

        double elapsedMilliseconds = (System.nanoTime() - startTime) / 1_000_000.0;
        String formattedTime = String.format("%.3f", elapsedMilliseconds);
        String output = sndRec + " " + formattedTime + " " + packet.toString();

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

        // 1. send fIN
        TCPPacket packet = new TCPPacket(seqNum, ackNum, false, false, true, null, System.nanoTime());
        seqNum++;

        //rec FIN and ACK
        TCPPacket recPacket = sendWithRetries(packet, socket, remoteIP);
        state = TcpStates.FIN_WAIT_1;


        // Case 1: Receive Ack for FIN. No FIN Flag
        if (recPacket.getAckFlag() && !recPacket.getFinFlag() && recPacket.getPayloadLength() == 0) {
            //System.out.println("case 1");
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
        else if (recPacket.getAckFlag() && recPacket.getFinFlag()) {
            //System.out.println("case 2");
            packet = new TCPPacket(seqNum, ackNum, false, true, false, null, recPacket.getTimeStamp()); 
            sendPacket(packet, socket, remoteIP);

            state = TcpStates.FIN_WAIT_2;
            //System.out.println("sender complete");
        }

        // 3. time out initiator. Wait to see if ACK was dropped or any other packets were received
        boolean ackReceived = false;
        int attempts = 0;

        while (!ackReceived && attempts < 5) {
            try {
                // Wait for any additional FIN from receiver
                socket.setSoTimeout(timeoutCalc.getTimeOut());
                TCPPacket finalFinRetry = receivePacket(socket);

                if (finalFinRetry != null && finalFinRetry.getFinFlag()) {
                    //System.out.println("Resending final ACK due to retransmitted FIN from receiver.");
                    TCPPacket finalAck = new TCPPacket(seqNum, finalFinRetry.getSeqNum() + 1, false, true, false, null, System.nanoTime());
                    sendPacket(finalAck, socket, remoteIP);
                    seqNum++;
                }
            } catch (SocketTimeoutException e) {
                ackReceived = true; 
            }

            attempts++;
        }

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
        // send ACK/FIN
        ackNum = finPacket.getSeqNum() + 1;
        TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, false, true, true, null, finPacket.getTimeStamp());
        seqNum++;

        sendPacket(ackPacket, socket, remoteIP);
        
        // Step 2: Wait for ACK of FIN packet
        boolean finAckReceived = false;
        try{
            while (!finAckReceived) {
                TCPPacket recPacket = receivePacket(socket);
                //System.out.println("waiting for final fin ack");
            
                if (recPacket.getAckFlag() && recPacket.getAckNum()+1 == seqNum) {
                    //System.out.println("final ack received");
                    // The receiver has received the ACK for its FIN
                    finAckReceived = true;
                    state = TcpStates.CLOSED;  // Transition to CLOSED state after FIN ACK is received
                }
            }
        }catch (IOException e){
            //
        }
        //System.out.println("Receiver has successfully completed the connection termination.");

    }
}