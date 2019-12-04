from mininet.topo import Topo
from mininet.net import Mininet
from mininet.node import Node
from mininet.log import setLogLevel, info
from mininet.cli import CLI
from mininet.node import RemoteController

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
    "A LinuxRouter connecting two IP subnets"

    def build( self, **_opts ):

        defaultIP = '10.0.1.1/24'  # IP address for r0-eth1
        router = self.addNode( 'r0', cls=LinuxRouter, ip=defaultIP )

        h1 = self.addHost( 'h1', ip='10.0.1.2/24', defaultRoute='via 10.0.1.1' )
        h2 = self.addHost( 'h2', ip='10.0.2.2/24', defaultRoute='via 10.0.2.1' )
        h3 = self.addHost( 'h3', ip='10.0.2.3/24', defaultRoute='via 10.0.2.1' )

        s1 = self.addSwitch('s1')

        self.addLink( h1, router, intfName2='r0-eth1', params2={ 'ip' : '10.0.1.1/24' } )
        self.addLink( s1, router, intfName2='r0-eth2', params2={ 'ip' : '10.0.2.1/24' } )

        self.addLink(h2, s1)
        self.addLink(h3, s1)

def run():
    "Test linux router"
    topo = NetworkTopo()
    net = Mininet( topo=topo , controller=lambda name: RemoteController( name, ip='127.0.0.1' ))  # controller is used by s1
    net.start()
    
    CLI( net )
    net.stop()

if __name__ == '__main__':
    setLogLevel( 'info' )
    run()