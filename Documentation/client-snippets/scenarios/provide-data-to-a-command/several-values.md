```kotlin
suspend fun provide(bureau: CreditBureau, risk: RiskModel): Pair<CreditScore, RiskBand> =
    bureau.score(applicant) to risk.band(applicant)

fun handle(score: CreditScore, band: RiskBand): LoanAssessment = LoanAssessment(loanId, score, band)
```
