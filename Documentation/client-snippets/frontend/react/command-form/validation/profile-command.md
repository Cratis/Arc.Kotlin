```kotlin
package myapp.profiles

import io.cratis.arc.artifacts.Command
import io.cratis.arc.validation.FluentModelValidator

data class ProfileDetails(val name: String, val email: String)

class UpdateProfileRules : FluentModelValidator<UpdateProfile>(UpdateProfile::class.java) {
    init {
        ruleFor("name").notEmpty().length(3, 100)
        ruleFor("email").notEmpty().emailAddress()
    }
}

@Command
data class UpdateProfile(val name: String, val email: String) {
    fun handle(): ProfileDetails = ProfileDetails(name, email)
}
```
