package ru.pathcreator.pyc.rpc.schema;

/**
 * Non-fatal startup-time validation issue, обнаруженная optional schema-layer.
 *
 * <p>Non-fatal startup-time validation issue discovered by the optional schema
 * registry layer.</p>
 *
 * <p>Такие issue нужны для deploy review, startup diagnostics и operator
 * awareness. Они не участвуют в transport path и не заменяют hard validation
 * exceptions для явно неверной конфигурации.</p>
 *
 * <p>These issues are meant for deploy review, startup diagnostics, and
 * operator awareness. They do not participate in the transport path and do not
 * replace hard validation exceptions for clearly invalid configurations.</p>
 *
 * @param code    стабильный короткий код warning-класса / stable short code for the warning class
 * @param message human-readable описание warning / human-readable warning description
 */
public record RpcValidationIssue(String code, String message) {
}