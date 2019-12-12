from mininet.topo import Topo
from mininet.net import Mininet
from mininet.node import Node
from mininet.log import setLogLevel, info
from mininet.cli import CLI
from mininet.node import RemoteController
from mininet.node import OVSBridge

class LinuxRouter( Node ):
    "A Node with IP forwarding enabled."

    def config( self, **params ):
        super( LinuxRouter, self).config( **params )
        # Enable forwarding on the router
        self.cmd( 'sysctl net.ipv4.ip_forward=1' )
        self.cmd( 'python vrrpc.py &' )

    def terminate( self ):
        self.cmd( 'sysctl net.ipv4.ip_forward=0' )
        super( LinuxRouter, self ).terminate()

class LinuxServer( Node ):
	def config( self, **params):
		super( LinuxServer, self ).config( **params )
		# add the two routers has gateways for troubleshooting
		self.cmd("route add default gw 10.0.2.1")
		self.cmd("route add default gw 10.0.2.2")

	def terminate( self ):
		super( LinuxServer, self ).terminate()

class NetworkTopo( Topo ):
    "Two LinuxRouter connecting two IP subnets"

    def build( self, **_opts ):
        r1 = self.addNode( 'r1', cls=LinuxRouter, ip='10.0.1.1/24', mac='00:00:00:00:00:01' )
	r2 = self.addNode( 'r2', cls=LinuxRouter, ip='10.0.1.2/24', mac='00:00:00:00:00:02' )

	# network A
        h1 = self.addHost( 'h11', ip='10.0.1.3/24', mac='00:00:00:00:00:11', defaultRoute='via 10.0.1.10' )
        h2 = self.addHost( 'h12', ip='10.0.1.4/24', mac='00:00:00:00:00:12', defaultRoute='via 10.0.1.10' )
        h3 = self.addHost( 'h13', ip='10.0.1.5/24', mac='00:00:00:00:00:13', defaultRoute='via 10.0.1.10' )

        ss = self.addSwitch('s1')

        self.addLink( ss, r1, intfName2='r1-eth1', params2={ 'ip' : '10.0.1.1/24' } )
        self.addLink( ss, r2, intfName2='r2-eth1', params2={ 'ip' : '10.0.1.2/24' } )

	self.addLink(h1, ss)
        self.addLink(h2, ss)
        self.addLink(h3, ss)

	# network B
	s1 = self.addNode( 'h21', cls=LinuxServer, ip='10.0.2.3/24', mac='00:00:00:00:00:21' )
	s2 = self.addNode( 'h22', cls=LinuxServer, ip='10.0.2.4/24', mac='00:00:00:00:00:22' )

	ls = self.addSwitch('s2', cls=OVSBridge) # it acts as a normal switch

	self.addLink( ls, r1, intfName2='r1-eth2', params2={ 'ip' : '10.0.2.1/24' } )
	self.addLink( ls, r2, intfName2='r2-eth2', params2={ 'ip' : '10.0.2.2/24' } )

	self.addLink(s1, ls)
	self.addLink(s2, ls)

def run():
	"Test net"
    	topo = NetworkTopo()
    	net = Mininet( topo=topo , controller=lambda name: RemoteController( name, ip='127.0.0.1' ))
    	net.start()

    	CLI( net )
    	net.stop()

if __name__ == '__main__':
	setLogLevel( 'info' )
    	run()
