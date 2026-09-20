```kotlin
@Command
data class AssessLoan(val loanId: LoanId, val applicant: ApplicantId) {
    suspend fun provide(bureau: CreditBureau): CreditScore = bureau.score(applicant)

    fun handle(creditScore: CreditScore): LoanAssessment = LoanAssessment(loanId, creditScore)
}
```
