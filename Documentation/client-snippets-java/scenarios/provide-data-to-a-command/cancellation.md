```java
@Command
public record AssessLoan(LoanId loanId, ApplicantId applicant) {
    // No token parameter: Arc awaits the returned stage inside its command coroutine and cancels
    // that stage when the request is cancelled. It cannot stop work the stage does not own.
    public CompletionStage<CreditScore> provide(CreditBureau bureau) {
        return bureau.scoreAsync(applicant);
    }

    public CompletionStage<LoanAssessment> handle(CreditScore creditScore) {
        return CompletableFuture.completedFuture(new LoanAssessment(loanId, creditScore));
    }
}
```
