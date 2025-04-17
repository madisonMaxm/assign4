/**
 * TCPend builds UDP functionality on top of UDP.
 * 
 * The class contains both sender and receiver endpoints.
 * 
 * @author Maxwell meller
 */
import java.io.*;
import java.net.*;
import java.util.Arrays;

public class TCPend{

    public static void main(String[] args) {

        final String MODE;
        final String remoteIP;
        final String port;
        final String remotePort;
        final String fileName;
        final String mtu;
        final String sws;

        //sender mode
        if (args.length == 12){
            MODE = "sender";
            System.out.println("Sender initialization");

            if (!args[0].equals("-p") || !args[2].equals("-s") || !args[4].equals("-a") || !args[6].equals("-f") || !args[8].equals("-m") || !args[10].equals("-c")) {
                System.err.println(args[0] + " " + args[2] + " " + args[4] + " " + args[6] + " " + args[8] + " " + args[10]);
                System.out.println("Error: Arguments out of order");
				System.exit(1);
            }

            port = args[1];
            remoteIP = args[3];
            remotePort = args[5];
            fileName = args[7];
            mtu = args[9];
            sws = args[11];

            try {
                DatagramSocket socket = new DatagramSocket(Integer.parseInt(port));

                FileInputStream fis = new FileInputStream(fileName);
                
                byte[] buffer = new byte[Integer.parseInt(mtu)];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {

                    byte[] payload = Arrays.copyOf(buffer, bytesRead);

                    System.out.println("sending packet");

                    System.out.println("Bytes read from file: " + bytesRead);
    System.out.println("Payload (first few bytes): " + Arrays.toString(Arrays.copyOf(payload, Math.min(10, payload.length))));


                    TCPPacket tPacket = new TCPPacket
                    (Integer.parseInt(mtu), 0, true, false, false, payload); 

                    byte[] serialized = tPacket.serialize();
                    System.out.println("Serialized packet length: " + serialized.length);

                    DatagramPacket packet = new DatagramPacket(serialized, 0, serialized.length, InetAddress.getByName(remoteIP), Integer.parseInt(remotePort));
                    socket.send(packet);

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

        //receiver mode
        else if (args.length == 8){
            MODE = "receiver";
            System.out.println("Receiver initalization");

            if (!args[0].equals("-p") || !args[2].equals("-m") || !args[4].equals("-c") || !args[6].equals("-f"))
            {
                System.out.println("Error: Arguments out of order");
				System.exit(1);
            }

            port = args[1];
            mtu = args[3];
            sws = args[5];
            fileName = args[7];

            try {
                DatagramSocket datagramSocket = new DatagramSocket(Integer.parseInt(port));
                System.out.println("Receiver on port: " + port);

                FileOutputStream fos = new FileOutputStream(fileName);

                byte[] buffer = new byte[Integer.parseInt(mtu)];                
                
                while (true) {

                    System.out.println("Receiving packet");

                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                                
                    datagramSocket.receive(packet);

                    System.out.println("[Receiver] Packet received from " + packet.getAddress().getHostAddress() + ":" + packet.getPort());

                    System.out.println("[Receiver] Packet length: " + packet.getLength());
        
                    if (packet.getLength() == 0) {
                        // empty packet signals end of file
                        break;
                    }

                    byte[] data = packet.getData();                    

                    TCPPacket tPacket = new TCPPacket(Integer.parseInt(mtu), data).deserialize();

                    System.out.println("[Receiver] Deserialized TCPPacket — SeqNum: " + tPacket.getSeqNum() + ", AckNum: " + tPacket.getAckNum());

                    InetAddress senderAddress = packet.getAddress();
                    int senderPort = packet.getPort();

                    fos.write(packet.getData(), 0, packet.getLength());
                }
                
                fos.close();
                datagramSocket.close();
            } catch (IOException e){
                System.out.println("Error listening on port: " + e);
			}
            finally {
                
                System.exit(0);
            }

        }

        else{
            System.out.println("Error: missing or additional arguments");
			System.exit(1);
        }
    }
}