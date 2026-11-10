package edu.kit.aifb.solid.n3patch.parsers;

public class N3BadPatchException extends RuntimeException {
    public N3BadPatchException(String message) {
        super(message);
    }

    public N3BadPatchException(String message, Throwable cause) {
        super(message, cause);
    }
}