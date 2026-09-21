```kotlin
@Test
fun `handle returns the assessment`() {
    val loanId = LoanId(UUID.randomUUID())
    val command = AssessLoan(loanId, ApplicantId(UUID.randomUUID()))

    val assessment = command.handle(CreditScore(800))

    assertEquals(LoanAssessment(loanId, CreditScore(800)), assessment)
}
```
