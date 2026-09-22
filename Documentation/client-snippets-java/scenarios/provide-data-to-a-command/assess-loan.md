```java
@Command
public record AssessLoan(LoanId loanId, ApplicantId applicant) {
    public CreditScore provide(CreditBureau bureau) {
        return bureau.score(applicant);
    }

    public LoanAssessment handle(CreditScore creditScore) {
        return new LoanAssessment(loanId, creditScore);
    }
}
```
