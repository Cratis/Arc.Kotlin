```java
// An Object return lets provide hand back either the value handle consumes or a
// control signal; a returned ValidationResult rejects before handle runs.
public Object provide(CreditBureau bureau) {
    var score = bureau.score(applicant);
    return score == null
        ? ValidationResult.error("No credit history", List.of("applicant"))
        : score;
}

public LoanAssessment handle(CreditScore creditScore) {
    return new LoanAssessment(loanId, creditScore);
}
```
