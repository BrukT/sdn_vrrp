import socket
import time

DST_ADDR = '10.0.1.255'
DST_PORT = 2020

i = 0

while True:
    print(i, 'sending advertisement...')
    i = i + 1

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
    	sock.sendto('ADV\n', (DST_ADDR, DST_PORT))
    except Exception as e:
		print(e)

    time.sleep(1)


