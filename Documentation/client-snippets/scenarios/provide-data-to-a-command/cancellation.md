```kotlin
@Command
data class AssessLoan(val loanId: LoanId, val applicant: ApplicantId) {
    // No token parameter: a suspending provide or handle runs inside Arc's command
    // coroutine, so cancelling the request cancels the work it is awaiting.
    suspend fun provide(bureau: CreditBureau): CreditScore = bureau.score(applicant)

    suspend fun handle(creditScore: CreditScore): LoanAssessment {
        currentCoroutineContext().ensureActive()
        return LoanAssessment(loanId, creditScore)
    }
}
```
