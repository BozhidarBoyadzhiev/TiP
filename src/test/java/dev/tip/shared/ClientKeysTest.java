package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientKeysTest {

    @Test
    void ipv4AddressIsItsOwnKey() {
        assertThat(ClientKeys.of("198.18.0.7")).isEqualTo("198.18.0.7");
        assertThat(ClientKeys.of("198.18.0.8")).isNotEqualTo(ClientKeys.of("198.18.0.7"));
    }

    @Test
    void ipv6AddressIsKeyedByItsSlash64() {
        assertThat(ClientKeys.of("2001:db8:1:2::1")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKeys.of("2001:0db8:0001:0002:ffff:eeee:dddd:cccc")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKeys.of("2001:DB8:1:2:0:0:0:9")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKeys.of("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3::/64");
        assertThat(ClientKeys.of("::1")).isEqualTo("0:0:0:0::/64");
    }

    @Test
    void ipv6ZoneIsIgnored() {
        assertThat(ClientKeys.of("fe80::1%1")).isEqualTo("fe80:0:0:0::/64");
    }

    @Test
    void ipv4MappedAddressCountsAsItsIpv4Address() {
        assertThat(ClientKeys.of("::ffff:198.18.0.7")).isEqualTo("198.18.0.7");
    }

    @Test
    void somethingThatIsNoAddressStaysAsItIs() {
        assertThat(ClientKeys.of(null)).isNull();
        assertThat(ClientKeys.of("not:an:address:at:all")).isEqualTo("not:an:address:at:all");
    }
}
