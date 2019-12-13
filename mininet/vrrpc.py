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
    	sock.sendto('ADV', (DST_ADDR, DST_PORT))
    except Exception as e:
	pass    # don't crash because network is down -- I mean, we need to test you, don't we?

    time.sleep(1)


