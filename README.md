# Jenkins ECS Shared Pipeline
Shared library for ECS jenkins declarative pipelines.

## Benefits of declarative pipelines:
1. Maintainability
    * Jenkinsfiles are in SCM with the app
2. Parallel execution of stages
    * compile rails assets and webpack at the same time
3. Better log viewing via Blue Ocean
    * output is separated by stages

## Overview
Modules are namespaced under the foo variable in the vars directory. After
importing the library, a build can be configured like so.

```groovy
railsPipeline {
  agent = 'jenkins-ecs-rails'
  repo = 'org/bads'
  deployEnvs = ['preview', 'staging']
  app = 'my-awesome-app'
  webpack = false
}
```

## Modules
### Database
* #backup - for running the backup script in the production environment

### GitHub
* #post - post statuses to github
* #notifySuccess - send build notifications to Teams channel
* #notifyFailure
* #notifyAbort

### Node
* #yarnInstall
* #yarnTest

### Pcf
* #afterParty
* #deploy
* #login
* #logout

### Ruby
* #assetsPrecompile
* #brakeman
* #brakemanInstall
* #bundleInstall
* #coverageRspec
* #dbRebuild
* #gemCheck
* #gemUpdate
* #rcov
* #rspec
* #rubo
* #rubocopInstall
* #ruboViolationCount
* #rvmInstall
* #webpack
* #webpackerCompile
* #yarnInstall

### Util
* #success - return if command exits with code 0
* #shellCmd - return stdout of command
* #json - return groovy Map of parsed json from command output

## Testing
The library uses [jenkins-spock](https://github.com/ExpediaGroup/jenkins-spock)
for testing.

### run tests
$ mvn test

### run tests for a single class
mvn -Dtest=GitHubSpec test
