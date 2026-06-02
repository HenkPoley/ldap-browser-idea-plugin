package org.majki.intellij.ldapbrowser.ldap;

import java.io.Serializable;
import java.util.Locale;
import java.util.List;


public class LdapAttribute implements Serializable {

    public static class Value implements Serializable {

        private boolean _null;
        private boolean humanReadable;
        private String stringValue;
        private byte[] byteArrayValue;

        protected Value(boolean isNull, boolean isHumanReadable, String stringValue, byte[] byteArrayValue) {
            this._null = isNull;
            this.humanReadable = isHumanReadable;
            this.stringValue = stringValue;
            this.byteArrayValue = byteArrayValue;
        }

        public boolean isNull() {
            return _null;
        }

        public boolean isHumanReadable() {
            return humanReadable;
        }

        public String asString() {
            return stringValue;
        }

        public String asDisplayString(String attributeName) {
            if (_null) {
                return null;
            }
            String normalizedName = attributeName == null ? "" : attributeName.toLowerCase(Locale.ROOT);
            byte[] bytes = binaryBytes();
            if (isGuidAttribute(normalizedName) && bytes.length == 16) {
                return guidString(bytes);
            }
            if (isSidAttribute(normalizedName) && bytes.length >= 8) {
                return sidString(bytes);
            }
            if (isBinaryAttribute(normalizedName)) {
                return "0x" + hex(bytes);
            }
            if (humanReadable) {
                return stringValue;
            }
            return "0x" + hex(bytes);
        }

        private byte[] binaryBytes() {
            if (stringValue != null && byteArrayValue != null && byteArrayValue.length != stringValue.length()) {
                return lowCharacterBytes(stringValue);
            }
            if (byteArrayValue != null) {
                return byteArrayValue;
            }
            if (stringValue == null) {
                return new byte[0];
            }
            return lowCharacterBytes(stringValue);
        }

        private byte[] lowCharacterBytes(String value) {
            byte[] bytes = new byte[value.length()];
            for (int i = 0; i < value.length(); i++) {
                bytes[i] = (byte) value.charAt(i);
            }
            return bytes;
        }

        private boolean isGuidAttribute(String attributeName) {
            return "objectguid".equals(attributeName)
                || "msexchmailboxguid".equals(attributeName)
                || "ms-ds-consistencyguid".equals(attributeName)
                || "msdfsr-replicationgroupguid".equals(attributeName)
                || "msdfsr-contentsetguid".equals(attributeName)
                || "pktguid".equals(attributeName);
        }

        private boolean isSidAttribute(String attributeName) {
            return "objectsid".equals(attributeName)
                || "msexchmasteraccountsid".equals(attributeName);
        }

        private boolean isBinaryAttribute(String attributeName) {
            return "msexchmailboxsecuritydescriptor".equals(attributeName)
                || "logonhours".equals(attributeName)
                || "pkt".equals(attributeName)
                || "dnsproperty".equals(attributeName)
                || "dnsrecord".equals(attributeName);
        }

        private String guidString(byte[] bytes) {
            return String.format("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                bytes[3] & 0xff, bytes[2] & 0xff, bytes[1] & 0xff, bytes[0] & 0xff,
                bytes[5] & 0xff, bytes[4] & 0xff,
                bytes[7] & 0xff, bytes[6] & 0xff,
                bytes[8] & 0xff, bytes[9] & 0xff,
                bytes[10] & 0xff, bytes[11] & 0xff, bytes[12] & 0xff, bytes[13] & 0xff, bytes[14] & 0xff, bytes[15] & 0xff);
        }

        private String sidString(byte[] bytes) {
            long authority = 0;
            for (int i = 2; i < 8; i++) {
                authority = (authority << 8) | (bytes[i] & 0xffL);
            }
            int subAuthorityCount = bytes[1] & 0xff;
            StringBuilder sid = new StringBuilder("S-")
                .append(bytes[0] & 0xff)
                .append('-')
                .append(authority);
            int maxSubAuthorityCount = Math.min(subAuthorityCount, (bytes.length - 8) / 4);
            for (int i = 0; i < maxSubAuthorityCount; i++) {
                int offset = 8 + (i * 4);
                long subAuthority = (bytes[offset] & 0xffL)
                    | ((bytes[offset + 1] & 0xffL) << 8)
                    | ((bytes[offset + 2] & 0xffL) << 16)
                    | ((bytes[offset + 3] & 0xffL) << 24);
                sid.append('-').append(subAuthority);
            }
            if (maxSubAuthorityCount < subAuthorityCount) {
                sid.append(" (truncated)");
            }
            return sid.toString();
        }

        private String hex(byte[] bytes) {
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        }

        public byte[] asByteArray() {
            return byteArrayValue;
        }

        protected void setValue(String value) {
            stringValue = value;
            byteArrayValue = value.getBytes();
        }

        protected void setValue(byte[] value) {
            stringValue = new String(value);
            byteArrayValue = value;
        }
    }

    private String name;
    private String upName;
    private boolean humanReadable;
    private List<Value> values;

    protected LdapAttribute(String name, String upName, boolean humanReadable, List<Value> values) {
        this.name = name;
        this.upName = upName;
        this.humanReadable = humanReadable;
        this.values = values;
    }

    public String name() {
        return name;
    }

    public String upName() {
        return upName;
    }

    public boolean isHumanReadable() {
        return humanReadable;
    }

    public List<Value> values() {
        return values;
    }

    public Value firstValue() {
        return values.get(0);
    }

    public String[] valuesAsString() {
        String[] stringValues = new String[values.size()];
        for (int i = 0; i < values.size(); i++) {
            stringValues[i] = values().get(i).asString();
        }
        return stringValues;
    }

}
