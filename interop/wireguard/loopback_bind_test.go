package interop

import (
	"net"
	"net/netip"
	"sync"

	"golang.zx2c4.com/wireguard/conn"
)

// loopbackBind is a minimal IPv4 UDP socket for sandboxes that reject the default bind's
// advanced socket options. It changes only how encrypted datagrams reach the OS; the
// WireGuard handshake, encryption, peer authorization and packet routing are unchanged.
type loopbackBind struct {
	mu   sync.Mutex
	conn *net.UDPConn
}

type loopbackEndpoint struct{ dst netip.AddrPort }

func (e loopbackEndpoint) ClearSrc()           {}
func (e loopbackEndpoint) SrcToString() string { return "" }
func (e loopbackEndpoint) DstToString() string { return e.dst.String() }
func (e loopbackEndpoint) DstToBytes() []byte  { b, _ := e.dst.MarshalBinary(); return b }
func (e loopbackEndpoint) DstIP() netip.Addr   { return e.dst.Addr() }
func (e loopbackEndpoint) SrcIP() netip.Addr   { return netip.Addr{} }

func (b *loopbackBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.conn != nil {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)})
	if err != nil {
		return nil, 0, err
	}
	b.conn = c
	receive := func(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
		n, from, err := c.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		eps[0] = loopbackEndpoint{dst: netip.AddrPortFrom(from.Addr().Unmap(), from.Port())}
		return 1, nil
	}
	return []conn.ReceiveFunc{receive}, uint16(c.LocalAddr().(*net.UDPAddr).Port), nil
}

func (b *loopbackBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.conn == nil {
		return nil
	}
	err := b.conn.Close()
	b.conn = nil
	return err
}

func (b *loopbackBind) SetMark(uint32) error { return nil }
func (b *loopbackBind) BatchSize() int       { return 1 }

func (b *loopbackBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	b.mu.Lock()
	c := b.conn
	b.mu.Unlock()
	if c == nil {
		return net.ErrClosed
	}
	dst := ep.(loopbackEndpoint).dst
	for _, buf := range bufs {
		if _, err := c.WriteToUDPAddrPort(buf, dst); err != nil {
			return err
		}
	}
	return nil
}

func (b *loopbackBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	dst, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return loopbackEndpoint{dst: dst}, nil
}
