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

        final String mode;
        final String remoteIP;
        final String port;
        final String remotePort;
        final String fileName;
        final String mtu;
        final String sws;

        //sender mode
        if (args.length == 12){

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

            TCPMode sender = new TCPMode(port, remoteIP, remotePort, fileName, mtu, sws);

            sender.run();
        }

        //receiver mode
        else if (args.length == 8){
            //System.out.println("Receiver initalization");

            if (!args[0].equals("-p") || !args[2].equals("-m") || !args[4].equals("-c") || !args[6].equals("-f"))
            {
                System.out.println("Error: Arguments out of order");
				System.exit(1);
            }

            port = args[1];
            mtu = args[3];
            sws = args[5];
            fileName = args[7];

            TCPMode receiver = new TCPMode(port, mtu, sws, fileName);
            receiver.run();
        }

        else{
            System.out.println("Error: missing or additional arguments");
			System.exit(1);
        }
    }
}