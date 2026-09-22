```java
@Test
void handleReturnsTheAssessment() {
    var loanId = new LoanId(UUID.randomUUID());
    var command = new AssessLoan(loanId, new ApplicantId(UUID.randomUUID()));

    var assessment = command.handle(new CreditScore(800));

    assertEquals(new LoanAssessment(loanId, new CreditScore(800)), assessment);
}
```
