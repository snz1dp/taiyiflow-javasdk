package io.taiyiflow.sdk.browser;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BrowserNetworkPolicy {
    private final Set<String> allowedHosts;
    private final List<Cidr> allowedCidrs;

    public BrowserNetworkPolicy(BrowserSettings settings) {
        this.allowedHosts = settings.getAllowedHosts();
        this.allowedCidrs = settings.getAllowedCidrs().stream().map(Cidr::parse).toList();
    }

    public URI validate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A navigation URL is required");
        }
        if ("about:blank".equals(value)) {
            return URI.create(value);
        }
        URI uri = URI.create(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new SecurityException("Only HTTP(S) URLs without userinfo and about:blank are allowed");
        }
        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        if (allowedHosts.contains(host)) {
            return uri;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!allowed(address) && !matchesAllowlist(address)) {
                    throw new SecurityException("Navigation to a private or local address is blocked");
                }
            }
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("Navigation target could not be validated", exception);
        }
        return uri;
    }

    public boolean isAllowed(String value) {
        try {
            validate(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean matchesAllowlist(InetAddress address) {
        return allowedCidrs.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static boolean allowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            return false;
        }
        return true;
    }

    private record Cidr(byte[] network, int prefixLength) {
        private static Cidr parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid CIDR allowlist entry");
            try {
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > address.length * 8) {
                    throw new IllegalArgumentException("Invalid CIDR prefix");
                }
                return new Cidr(address, prefix);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid CIDR allowlist entry", exception);
            }
        }

        private boolean contains(InetAddress candidate) {
            byte[] address = candidate.getAddress();
            if (address.length != network.length) return false;
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
