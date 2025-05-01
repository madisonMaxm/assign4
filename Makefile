sender: ./src/TCPend.class
	java -cp ./src TCPend -p 55000 -s 127.0.0.1 -a 55001 -f testSendLong.txt -m 1000 -c 10

receiver: ./src/TCPend.class
	java -cp ./src TCPend -p 55001 -m 1000 -c 10 -f testReceive.txt


compile: ./src/TCPend.class

./src/TCPend.class: ./src/TimeoutCalc.class ./src/TCPend.java ./src/TCPPacket.class ./src/TCPMode.class
	javac -d ./src -cp ./src ./src/TCPend.java

./src/TCPPacket.class: ./src/TCPPacket.java
	javac -d ./src -cp ./src ./src/TCPPacket.java

./src/TCPMode.class: ./src/TCPMode.java
	javac -d ./src -cp ./src ./src/TCPMode.java

./src/TimeoutCalc.class: ./src/TimeoutCalc.java
	javac -d ./src -cp ./src ./src/TimeoutCalc.java

clean:
	rm -f ./src/*.class
