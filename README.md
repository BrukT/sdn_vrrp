# Simulating Virtual Router Redundancy Protocol in a SDN environment

The aim of this project is to offer a centralized solution in a Software Defined Network environment for solving the First Hop Redundancy (FHR) issue. It has been realized using the Floodlight Java framework which implements OpenFlow controllers.

## Controller

Floodlight provides itself an implementation of some basic operations performed by controllers. We added our custom module in the pipeline adding the line `it.unipi.ce.anaws.vrrm.VirtualRouterRedundancyManager` in two configuration files just at the end of the list:
  - `src/main/resources/META-INF/services/net.floodlight.core.module.IFloodlightModule`;
  - `src/main/resources/floodlightdefault.properties`.
  
Running the application we will make the controller available for connections on `127.0.0.1:6653`.

## Network
The network is simulated through the mininet tool which also allows us to build our own net through a python script. Mininet can provide a connection between the switches in the net and the OpenFlow controller. Our purpose is to use the controller only to manage one of the two switches present in the net, in particular the switch in the subnet `10.0.1.0/24`, as shown in the picture.

![Network](/doc/img/vrrm.png)

For running the net you must go in the `mininet` directory and type:
```
$ sudo python net.py
```
It will connect the controller by itself.

Each host in the subnet controlled by the OpenFlow controller has been configured with the default gateway `10.0.1.10` which corresponds to the IP address of the `VIRTUAL_ROUTER`.

## Centralized Redundancy Protocol

Both routers involved in the protocol start sending UDP advertisements in `broadcast` to port `2020` with an interval of `1` second. The controller will elect as _Master_ the router from which it received the _ADV_ first. The other will become the _Backup_ router. 

The controller maintains the following data:
- `MASTER_ROUTER`: represents the actual MAC and IP address of the elected _Master_ router;
- `LAST_MASTER_ADV`: represents the last time that the controller has received an _ADV_ from the _Master_;
- `MASTER_DOWN_INTERVAL`: represents the time interval to decleare _Master_ no more active. Usually it is set accordingly to the interval time between two consecutive _ADVs_ (`1` second in our case).

Every time the controller receives an _ADV_ form the _Master_, it updates the `LAST_MASTER_ADV`.

If the controller receives an _ADV_ from the _Backup_ router and it is not receiving _ADVs_ from the _Master_ for a period greater than the `MASTER_DOWN_INTERVAL`, it will promote the _Backup_ to _Master_. Moreover the controller will flood the new _Master_ MAC address to all the hosts by means of a _Gratuitous ARP Reply_.

### Preemption mode

It is possible to run the protocol with a _preemption mode_ in which it is possible to set a `PRIMARY_ROUTER` which, when active, must be the `MASTER_ROUTER`.


### Algorithm in pseudocode

```
on_receive(source):
  if MASTER_ROUTER is not set yet:
    MASTER_ROUTER = source
    LAST_MASTER_ADV = time.now
    
  else if source is MASTER_ROUTER:
    LAST_MASTER_ADV = time.now
    
  else if LAST_MASTER_ADV - time.now > 1:
    MASTER_ROUTER = source
    LAST_MASTER_ADV = time.now
    flood(MASTER_ROUTER_MAC)
    
  else if preemption and source is PRIMARY_ROUTER and PRIMARY_ROUTER is not MASTER_ROUTER:
    MASTER_ROUTER = source
    LAST_MASTER_ADV = time.now
    flood(MASTER_ROUTER_MAC)
```

### ARP Replies

Whenever the controller gets an ARP request for knowing which is the MAC address of the `VIRTUAL_ROUTER`, the controller will reply with the MAC address of the current `MASTER_ROUTER`.

### Configuration

The controller can be configured in order to adapt it for any network. The configuration is offered by means of _REST_ resources which are exposed at `http://127.0.0.1:8080/vrrm/config` in a _JSON_ format.
The default configuration is represented by the following json object:

```
{
  "virtual_host_ip": "10.0.1.10"
  "primary_router_ip": "10.0.1.1"
  "preemption_mode": false
}
```

## Troubleshooting

In order to check the correcteness of the protocol just generate a data flow between the two networks using _iperf_ tool. In mininet, open `xterm` for `h11` and `h21`. 

On `h21` type:

```
$ iperf -u -s -i 1
```

and then on `h11` type:

```
$ iperf -u -c 10.0.2.3 -i 1 -t 120
```

To simulate a failure of the link between `S1` and `R1` type:

```
mininet> link s1 r1 down
```

In the _iperf_ server you should notice a decreasing of traffic for a small period of time and then, as soon as the new _Master_ is elected, the flow restarts to increase.
