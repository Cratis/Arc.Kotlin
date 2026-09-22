```java
public CommandProvidedValues provide(CreditBureau bureau, RiskModel risk) {
    return CommandProvidedValues.of(bureau.score(applicant), risk.band(applicant));
}

public LoanAssessment handle(CreditScore score, RiskBand band) {
    return new LoanAssessment(loanId, score, band);
}
```
