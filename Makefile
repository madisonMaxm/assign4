run:
	echo "Hello from make!"

sender: ./src/TCPend.class
	java -cp ./src TCPend -p 55000 -s 127.0.0.1 -a 55001 -f testSend.txt -m 1430 -c 1000

receiver: ./src/TCPend.class
	java -cp ./src TCPend -p 55001 -m 1430 -c 1000 -f testReceive.txt


compile: ./src/TCPend.class
	javac -d ./src -cp ./src ./src/TCPend.java

./src/TCPend.class: ./src/TCPend.java ./src/TCPPacket.class ./src/TCPMode.class
	javac -d ./src -cp ./src ./src/TCPend.java

./src/TCPPacket.class: ./src/TCPPacket.java
	javac -d ./src -cp ./src ./src/TCPPacket.java

./src/TCPMode.class: ./src/TCPMode.java
	javac -d ./src -cp ./src ./src/TCPMode.java

clean:
	rm -f ./src/*.class
