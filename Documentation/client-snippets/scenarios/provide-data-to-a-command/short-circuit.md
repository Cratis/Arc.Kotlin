```kotlin
// An Any return lets provide hand back either the value handle consumes or a
// control signal; a returned ValidationResult rejects before handle runs.
suspend fun provide(bureau: CreditBureau): Any =
    bureau.score(applicant)
        ?: ValidationResult.error("No credit history", listOf("applicant"))

fun handle(creditScore: CreditScore): LoanAssessment = LoanAssessment(loanId, creditScore)
```
