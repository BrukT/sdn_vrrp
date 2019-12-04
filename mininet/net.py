from mininet.topo import Topo
from mininet.net import Mininet
from mininet.node import Node
from mininet.log import setLogLevel, info
from mininet.cli import CLI
from mininet.node import RemoteController
from mininet.nodelib import LinuxBridge

class LinuxRouter( Node ):
    "A Node with IP forwarding enabled."

    def config( self, **params ):
        super( LinuxRouter, self).config( **params )
        # Enable forwarding on the router
        self.cmd( 'sysctl net.ipv4.ip_forward=1' )

    def terminate( self ):
        self.cmd( 'sysctl net.ipv4.ip_forward=0' )
        super( LinuxRouter, self ).terminate()

class NetworkTopo( Topo ):
    "Two LinuxRouter connecting two IP subnets"

    def build( self, **_opts ):
	# network A
        r1 = self.addNode( 'r1', cls=LinuxRouter, ip='10.0.1.1' )
	r2 = self.addNode( 'r2', cls=LinuxRouter, ip='10.0.1.2' )

        h1 = self.addHost( 'h11', ip='10.0.1.3/24', defaultRoute='via 10.0.1.1' )
        h2 = self.addHost( 'h12', ip='10.0.1.4/24', defaultRoute='via 10.0.1.1' )
        h3 = self.addHost( 'h13', ip='10.0.1.5/24', defaultRoute='via 10.0.1.1' )

        ss = self.addSwitch('s1')

        self.addLink( ss, r1, intfName2='r1-eth1', params2={ 'ip' : '10.0.1.1/24' } )
        self.addLink( ss, r2, intfName2='r2-eth1', params2={ 'ip' : '10.0.1.2/24' } )

	self.addLink(h1, ss)
        self.addLink(h2, ss)
        self.addLink(h3, ss)

	# network B
	s1 = self.addHost( 'h21', ip='10.0.2.3/24', defaultRoute='via 10.0.2.1' )
	s2 = self.addHost( 'h22', ip='10.0.2.4/24', defaultRoute='via 10.0.2.1' )

	ls = self.addSwitch('s2', cls=LinuxBridge) # it acts as a normal switch

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
