package org.lbee.protocol;

public enum TypeMessage {
    YES,
    NO,
    ID;

    @Override
    public String toString() {
        switch (this) {
            case YES:
                return "YES";
            case NO:
                return "NO";
            case ID:
                return "ID";
            default:
                throw new IllegalArgumentException("Unknown type");
        }
    }
}