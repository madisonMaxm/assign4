import java.io.*;
import java.net.*;

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

            if (args[0] != "-p" || args[2] != "-s" || args[4] != "-a" || args[6] != "-f" || args[8] != "-m" || args[10] != "-c") {
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
                Socket socket = new DatagramSocket(port);
                OutputStream out = socket.getOutputStream();

                File file = File(fileName);
                Long fileSize = file.length();
                FileReader fis = new FileInputStream(fileName); //TODO add path if needed
                
                byte[] buffer = new byte[1024];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    DatagramPacket packet = new DatagramPacket(buffer, bytesRead, remoteIP, remotePort);
                    socket.send(packet);
            }



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

            if (args[0] != "-p" || args[2] != "-m" || args[4] != "-c" || args[6] != "-f")
            {
                System.out.println("Error: Arguments out of order");
				System.exit(1);
            }

            port = args[1];
            mtu = args[3];
            sws = args[5];
            fileName = args[7];

            try {
                DatagramSocket datagramSocket = new DatagramSocket(port);
                datagramSocket.accept();

                FileOutputStream fos = new FileOutputStream(fileName);

                byte[] buffer = new byte[1024];
                
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
        
                    if (packet.getLength() == 0) {
                        // empty packet signals end of file
                        break;
                    }

                    fos.write(packet.getData(), 0, packet.getLength());

                }
                
                System.out.println("Server listening on port: " + port);
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