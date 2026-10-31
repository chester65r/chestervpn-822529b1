// Packet-level interoperability fixture for the WireGuard client/server blueprints.
//
// Two independent userspace WireGuard peers (wireguard-go, the engine embedded in the
// Android GoBackend) are configured only from wg-quick files produced by the app's
// shared generator. IP packets travel through gVisor netstack TUN devices and
// encrypted UDP on loopback. This is engine/protocol evidence, not Android evidence.
package interop

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type section map[string]string

func parse(t *testing.T, name string) (section, []section) {
	t.Helper()
	dir := os.Getenv("WG_FIXTURES")
	if dir == "" {
		t.Fatal("WG_FIXTURES is required; run interop/wireguard/run.sh")
	}
	file, err := os.Open(filepath.Join(dir, name))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	var iface section
	var peers []section
	var current section
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		switch {
		case line == "" || strings.HasPrefix(line, "#"):
		case line == "[Interface]":
			iface = section{}
			current = iface
		case line == "[Peer]":
			current = section{}
			peers = append(peers, current)
		default:
			key, value, ok := strings.Cut(line, "=")
			if !ok || current == nil {
				t.Fatalf("%s: malformed line", name)
			}
			current[strings.TrimSpace(key)] = strings.TrimSpace(value)
		}
	}
	if iface == nil || len(peers) == 0 {
		t.Fatalf("%s: needs [Interface] and [Peer]", name)
	}
	return iface, peers
}

func hexKey(t *testing.T, value string) string {
	raw, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(raw) != 32 {
		t.Fatalf("invalid key in fixture")
	}
	return hex.EncodeToString(raw)
}

type peer struct {
	dev  *device.Device
	net  *netstack.Net
	addr netip.Addr
}

// up converts a wg-quick file into the UAPI used by every wireguard-go host, including Android.
func up(t *testing.T, name string, listenPort int, endpoint string) peer {
	t.Helper()
	iface, peers := parse(t, name)
	prefix, err := netip.ParsePrefix(iface["Address"])
	if err != nil {
		t.Fatal(err)
	}
	mtu, err := strconv.Atoi(iface["MTU"])
	if err != nil {
		t.Fatal(err)
	}
	var dns []netip.Addr
	if iface["DNS"] != "" {
		dns = append(dns, netip.MustParseAddr(iface["DNS"]))
	}
	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{prefix.Addr()}, dns, mtu)
	if err != nil {
		t.Fatal(err)
	}
	var uapi strings.Builder
	fmt.Fprintf(&uapi, "private_key=%s\nlisten_port=%d\n", hexKey(t, iface["PrivateKey"]), listenPort)
	for _, p := range peers {
		fmt.Fprintf(&uapi, "public_key=%s\npreshared_key=%s\n", hexKey(t, p["PublicKey"]), hexKey(t, p["PresharedKey"]))
		if p["Endpoint"] != "" {
			fmt.Fprintf(&uapi, "endpoint=%s\n", endpoint)
		}
		if p["PersistentKeepalive"] != "" {
			fmt.Fprintf(&uapi, "persistent_keepalive_interval=%s\n", p["PersistentKeepalive"])
		}
		for _, cidr := range strings.Split(p["AllowedIPs"], ",") {
			fmt.Fprintf(&uapi, "allowed_ip=%s\n", strings.TrimSpace(cidr))
		}
	}
	dev := device.NewDevice(tunDev, &loopbackBind{}, device.NewLogger(device.LogLevelError, name+": "))
	if err := dev.IpcSet(uapi.String()); err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(dev.Close)
	return peer{dev: dev, net: tnet, addr: prefix.Addr()}
}

func freeUDPPort(t *testing.T) int {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// serve starts TCP echo, UDP echo and an authoritative DNS answer inside the server tunnel only.
func serve(t *testing.T, server peer) {
	tcp, err := server.net.ListenTCP(&net.TCPAddr{IP: server.addr.AsSlice(), Port: 7000})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { tcp.Close() })
	go func() {
		for {
			c, err := tcp.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	udp, err := server.net.ListenUDP(&net.UDPAddr{IP: server.addr.AsSlice(), Port: 7001})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	go func() {
		buf := make([]byte, 65535)
		for {
			n, from, err := udp.ReadFrom(buf)
			if err != nil {
				return
			}
			udp.WriteTo(buf[:n], from)
		}
	}()
	dns, err := server.net.ListenUDP(&net.UDPAddr{IP: server.addr.AsSlice(), Port: 53})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { dns.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := dns.ReadFrom(buf)
			if err != nil {
				return
			}
			var msg dnsmessage.Message
			if msg.Unpack(buf[:n]) != nil || len(msg.Questions) != 1 {
				continue
			}
			q := msg.Questions[0]
			msg.Header.Response, msg.Header.Authoritative = true, true
			if q.Name.String() == "service.example.com." && q.Type == dnsmessage.TypeA {
				msg.Answers = []dnsmessage.Resource{{
					Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
					Body:   &dnsmessage.AResource{A: server.addr.As4()},
				}}
			}
			if out, err := msg.Pack(); err == nil {
				dns.WriteTo(out, from)
			}
		}
	}()
}

func pair(t *testing.T, clientFile string) (peer, peer) {
	port := freeUDPPort(t)
	server := up(t, "server.conf", port, "")
	serve(t, server)
	client := up(t, clientFile, 0, fmt.Sprintf("127.0.0.1:%d", port))
	return server, client
}

func handshakeSeconds(t *testing.T, d *device.Device) int64 {
	state, err := d.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(state, "\n") {
		if v, ok := strings.CutPrefix(line, "last_handshake_time_sec="); ok {
			n, _ := strconv.ParseInt(v, 10, 64)
			return n
		}
	}
	return 0
}

func TestAuthorizedClientCarriesTCPUDPAndDNSPackets(t *testing.T) {
	server, client := pair(t, "client.conf")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	// TCP: 1 MiB crosses many MTU-sized IP packets in both directions.
	c, err := client.net.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(server.addr, 7000))
	if err != nil {
		t.Fatalf("TCP through tunnel: %v", err)
	}
	payload := make([]byte, 1<<20)
	rand.Read(payload)
	go func() { c.Write(payload) }()
	echo := make([]byte, len(payload))
	c.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := io.ReadFull(c, echo); err != nil || !bytes.Equal(payload, echo) {
		t.Fatalf("TCP echo mismatch: %v", err)
	}
	c.Close()

	// UDP: datagram boundaries are preserved, up to MTU minus IPv4+UDP headers.
	u, err := client.net.DialUDPAddrPort(netip.AddrPort{}, netip.AddrPortFrom(server.addr, 7001))
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	for _, size := range []int{1, 512, 1280 - 28} {
		msg := make([]byte, size)
		rand.Read(msg)
		u.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := u.Write(msg); err != nil {
			t.Fatal(err)
		}
		got := make([]byte, 2048)
		n, err := u.Read(got)
		if err != nil || !bytes.Equal(got[:n], msg) {
			t.Fatalf("UDP %d-byte datagram mismatch: %v", size, err)
		}
	}

	// DNS: the client's configured tunnel resolver answers over UDP inside the tunnel.
	addrs, err := client.net.LookupContextHost(ctx, "service.example.com")
	if err != nil || len(addrs) != 1 || addrs[0] != server.addr.String() {
		t.Fatalf("DNS through tunnel: %v %v", addrs, err)
	}

	if handshakeSeconds(t, client.dev) == 0 || handshakeSeconds(t, server.dev) == 0 {
		t.Fatal("both peers must record a completed handshake")
	}
}

func TestUnregisteredClientKeyIsRejected(t *testing.T) {
	server, client := pair(t, "intruder.conf")
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	if c, err := client.net.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(server.addr, 7000)); err == nil {
		c.Close()
		t.Fatal("unregistered client reached the server")
	}
	if handshakeSeconds(t, server.dev) != 0 {
		t.Fatal("server completed a handshake with an unknown key")
	}
}

func TestWrongPresharedKeyIsRejected(t *testing.T) {
	server, client := pair(t, "wrong-psk.conf")
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	if c, err := client.net.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(server.addr, 7000)); err == nil {
		c.Close()
		t.Fatal("client with wrong preshared key reached the server")
	}
	if handshakeSeconds(t, client.dev) != 0 {
		t.Fatal("client completed a handshake with the wrong preshared key")
	}
}

func TestClientCannotSpoofAnotherTunnelAddress(t *testing.T) {
	// Authorized key, but a source address outside that peer's server-side AllowedIPs.
	server, spoof := pair(t, "spoof.conf")
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	if c, err := spoof.net.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(server.addr, 7000)); err == nil {
		c.Close()
		t.Fatal("server accepted packets from an address outside the peer's AllowedIPs")
	}
}

func echoUDP(t *testing.T, server, client peer, wantSuccess bool) {
	t.Helper()
	u, err := client.net.DialUDPAddrPort(netip.AddrPort{}, netip.AddrPortFrom(server.addr, 7001))
	if err != nil { t.Fatal(err) }
	defer u.Close()
	u.SetDeadline(time.Now().Add(3 * time.Second))
	msg := []byte("native-auth-unique-probe")
	if _, err := u.Write(msg); err != nil { t.Fatal(err) }
	buf := make([]byte, 128)
	n, err := u.Read(buf)
	if wantSuccess && (err != nil || !bytes.Equal(buf[:n], msg)) { t.Fatal("authorized probe failed") }
	if !wantSuccess && err == nil { t.Fatal("revoked or stale credentials reached the server") }
}

func TestRevocationStopsAnAlreadyAuthenticatedDevice(t *testing.T) {
	server, client := pair(t, "client.conf")
	echoUDP(t, server, client, true)
	_, peers := parse(t, "server.conf")
	public := hexKey(t, peers[0]["PublicKey"])
	if err := server.dev.IpcSet("public_key=" + public + "\nremove=true\n"); err != nil { t.Fatal(err) }
	echoUDP(t, server, client, false)
	state, err := server.dev.IpcGet()
	if err != nil { t.Fatal(err) }
	if strings.Contains(state, "public_key=" + public) { t.Fatal("revoked peer remains authorized") }
}

func TestRotatedPSKRejectsOldCredentialsAndAcceptsNew(t *testing.T) {
	server, client := pair(t, "client.conf")
	echoUDP(t, server, client, true)
	_, peers := parse(t, "rotated-server.conf")
	public := hexKey(t, peers[0]["PublicKey"])
	// Remove existing session keys as well as changing the authorization secret.
	if err := server.dev.IpcSet("public_key=" + public + "\nremove=true\n"); err != nil { t.Fatal(err) }
	uapi := "public_key=" + public + "\npreshared_key=" + hexKey(t, peers[0]["PresharedKey"]) + "\nallowed_ip=" + peers[0]["AllowedIPs"] + "\n"
	if err := server.dev.IpcSet(uapi); err != nil { t.Fatal(err) }
	echoUDP(t, server, client, false)
	client.dev.Close()
	// Read the server's bound port without leaking its private-key-containing IPC state.
	state, err := server.dev.IpcGet()
	if err != nil { t.Fatal(err) }
	var port string
	for _, line := range strings.Split(state, "\n") { if p, ok := strings.CutPrefix(line, "listen_port="); ok { port = p } }
	if port == "" { t.Fatal("server port missing") }
	rotated := up(t, "rotated.conf", 0, "127.0.0.1:" + port)
	echoUDP(t, server, rotated, true)
	if handshakeSeconds(t, rotated.dev) == 0 { t.Fatal("rotated credentials did not authenticate") }
}

func TestWrongServerIdentityNeverAuthenticates(t *testing.T) {
	server, client := pair(t, "wrong-server.conf")
	echoUDP(t, server, client, false)
	if handshakeSeconds(t, client.dev) != 0 { t.Fatal("wrong server key authenticated") }
}
