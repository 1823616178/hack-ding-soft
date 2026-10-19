import socket

s = socket.socket()
s.settimeout(5)
try:
    s.connect(('182.92.162.37', 8888))
    print('Connect successful!')
    s.close()
except Exception as e:
    print('Connect failed:', type(e), e)
