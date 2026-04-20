package ru.pathcreator.pyc.rpc.schema;

/**
 * Non-fatal startup-time validation issue discovered by the optional schema
 * registry layer.
 *
 * <p>These issues are meant for deploy review, startup diagnostics, and
 * operator awareness. They do not participate in the transport path and do not
 * replace hard validation exceptions for clearly invalid configurations.</p>
 *
 * @param code    stable short code for the warning class
 * @param message human-readable warning description
 */
public record RpcValidationIssue(String code, String message) {
}