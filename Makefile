sender: ./src/TCPend.class
	java -cp ./src/TCPend.java -p 55000 -a 10.0.2.102 -f "../testSend.txt" -m 1000 -c 1000

receiver: ./src/TCPend.class
	java -cp ./src/TCPend.java -p 55000 -m 1000 -c 1000 -f "../testReceive.txt"

#Compile
./src/TCPend.class
	javac -d ./src -cp ./src/TCPend.java

clean:
	rm -f *.class
