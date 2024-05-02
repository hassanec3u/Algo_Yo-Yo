package org.lbee.protocol;

public enum StateNode {

    INCONNU,
    PUITS,
    SOURCE,
    INTERNE;


    @Override
    public String toString() {
        switch (this) {
            case INCONNU:
                return "inconnu";
            case PUITS:
                return "sink";
            case SOURCE:
                return "source";
            case INTERNE:
                return "internal";
            default:
                throw new IllegalArgumentException("Unknown type");
        }
    }
    }
