run:
	echo "Hello from make!"

sender: ./src/TCPend.class
	java -cp ./src TCPend -p 55000 -s 10.0.2.102 -a 55000 -f testSend.txt -m 1430 -c 1000

receiver: ./src/TCPend.class
	java -cp ./src TCPend -p 55000 -m 1430 -c 1000 -f testReceive.txt

./src/TCPend.class: ./src/TCPend.java
	javac -d ./src -cp ./src ./src/TCPend.java

clean:
	rm -f ./src/*.class
