package com.mycomp.foo

class Ruby extends Util {
  def rvmFile = 'rvm.env'
  def github

  Ruby(args) {
    this.script = args.script
    this.github = new GitHub(script: args.script)
  }

  // install rvm
  def rvmInstall() {
    def cmd = "source ~/.rvm/scripts/rvm && rvm use --install --create . && export > $rvmFile"
    script.sh("bash -c '$cmd'")
    sourceRvm()
    script.sh("sed -i '/library.jenkins-ecs-pipeline.version/d' $rvmFile")
  }

  // add rvm vars to env
  def sourceRvm() {
    def data = script.readFile(file: rvmFile).split("\n")

    for (int i = 0; i < data.size(); i++) {
      def d = data[i] - "declare -x "
      if (!d.contains("=")) continue

      def rvm = d.split("=")
      def key = rvm[0]
      def value = rvm[1].replace("\"", "")
      script.env[key] = value
    }
  }

  def gemUpdate() {
    script.sh('gem update --system $RUBY_GEMS_VERSION')
  }

  def rubocopInstall() {
    script.sh("""gem list --silent -i rubocop_rails -v \$RUBOCOP_RAILS_VERSION\
      || gem install rubocop_rails -v=\$RUBOCOP_RAILS_VERSION\
      --no-document""")
  }

  def bundleInstall() {
    def cmd = [
      'gem install bundler -v $BUNDLER_VERSION --no-document',
      'bundle install --jobs=4 --retry=4'
    ]
    script.sh(cmd.join('\n'))
  }

  def brakemanInstall(){
    def gemHome = shellCmd('gem env home')
    def reinstallBrakeman = success([
      "find $gemHome -name brakeman_pro_install",
      "-mtime -1 -type f -maxdepth 1 -exec false {} +"
    ].join(' '))

    if (reinstallBrakeman) {
      def installBrakeman = [
        'gem install brakeman-pro --no-document --source',
        'https://$BRAKEMAN_ID:$BRAKEMAN_PWD@brakemanpro.com/gems/ -f'
      ].join(' ')

      script.sh([
        installBrakeman,
        "touch $gemHome/brakeman_pro_install"
      ].join('\n'))
    }
  }

  // @param args [optional, Map]
  // @option args.railsEnv          [optional, String] pcf environment
  // @option args.disableDBEnvCheck [optional, bool]
  def dbRebuild(args=[:]) {
    def railsEnv = args.railsEnv ?: 'test'
    def cmd = ["RAILS_ENV=$railsEnv"]

    if (args.disableDBEnvCheck) {
      cmd << "DISABLE_DATABASE_ENVIRONMENT_CHECK=1"
    }

    cmd << "bundle exec rake db:drop db:create db:migrate"

    script.sh(cmd.join(' '))
  }

  // args.n
  def parallelTestSetup(args) {
    script.sh("RAILS_ENV=test bundle exec rake parallel:setup[${args.n}]")
  }

  // @param args [optional, Map]
  // @option args.railsEnv [optional, String] pcf environment
  // @option args.nodeEnv  [optional, String] node environment
  // @option args.maxSize  [optional, String] max old space size
  def webpack(args=[:]) {
    def nodeEnv = args.nodeEnv ?: 'production'
    def maxSize = args.maxSize ?: '2048'
    def webpackCmd = []

    if (args.railsEnv) {
      webpackCmd << "RAILS_ENV=${args.railsEnv}"
    }

    webpackCmd << "NODE_ENV=$nodeEnv"

    webpackCmd << "bin/webpack"

    script.sh([
      'node --version',
      "export NODE_OPTIONS='--max-old-space-size=$maxSize'",
      'rm -rf public/packs*',
      webpackCmd.join(' ')
    ].join('\n'))
  }

  def rspec() {
    script.sh('SIMPLECOV_FORMATTER=rcov bundle exec rspec -f d --color --tty spec --fail-fast')
  }

  def parallelTest(args) {
    script.sh([
      "RAILS_ENV=test SIMPLECOV_FORMATTER=rcov",
      "bundle exec parallel_test spec -n ${args.n} -t rspec"
    ].join(' '))
  }

  // @param args [required, Map]
  // @option args.repo [required, String] scm repository
  def coverageRspec(args) {
    def covPercent = shellCmd("jq -r '.result.covered_percent' coverage/.last_run.json")
    github.postPullRequestComment(
      repo: args.repo,
      comment: "RSpec coverage: **$covPercent%**"
    )
  }

  // @param args [optional, Map]
  // @option args.totalCoverage [optional, Map]
  // @option args.totalCoverage.healthy   [optional, Integer]
  // @option args.totalCoverage.unhealthy [optional, Integer]
  // @option args.totalCoverage.unstable  [optional, Map]
  // @option args.codeCoverage [optional, Map]
  // @option args.codeCoverage.healthy   [optional, Integer]
  // @option args.codeCoverage.unhealthy [optional, Integer]
  // @option args.codeCoverage.unstable  [optional, Integer]
  def rcov(args=[:]) {
    def totalCov = [healthy: 95, unhealthy: 92, unstable: 90]
    def codeCov = [healthy: 95, unhealthy: 92, unstable: 90]

    if (args.totalCoverage) {
      totalCov += args.totalCoverage
    }

    if (args.codeCoverage) {
      codeCov += args.codeCoverage
    }

    totalCov += [metric: 'TOTAL_COVERAGE']
    codeCov += [metric: 'CODE_COVERAGE']

    script.step([
      $class: 'RcovPublisher',
      reportDir: 'coverage/rcov',
      targets: [totalCov, codeCov]
    ])
  }

  // @param args [required, Map]
  // @option args.repo [required, String] scm repository
  def ruboViolationCount(args) {
    def rubocopViolations = shellCmd("cat rubocop/total-violations-count.txt")
    def comment = [
      'Rubocop violations:',
      "**$rubocopViolations/${script.env.RUBOCOP_THRESHOLD}**"
    ].join(' ')

    github.postPullRequestComment(
      repo: args.repo,
      comment: comment
    )
  }

  def rubo() {
    script.sh("""
      rubo _\$RUBOCOP_RAILS_VERSION_

      if [ -f rubocop/total-violations-count.txt ]; then
        echo "SUCCESS: rubocop/total-violations-count.txt found. Evaluating linting metrics."
        rubocop_violations_count=`cat rubocop/total-violations-count.txt`
        if [ "\$rubocop_violations_count" -le "\$RUBOCOP_THRESHOLD" ]; then
          echo "SUCCESS: You have not exceeded the limit of 25 offenses from rubocop."
        else
          echo "ERROR: Your total number of rubocop offenses exceeds the limit of 25 offenses. Please correct the issues ASAP.";
          (exit 1);
        fi
        else
          echo "WARNING: rubocop/total-violations-count.txt does not exist, which means you probably did not run rubocop properly. If you would like to post rubocop reports on your PRs, please configure this properly."
        fi
    """)
  }

  def gemCheck(){
    script.sh("""
      { echo "Confirming the app's gems are updated..."; } 2> /dev/null
      threshold=\$OUTDATED_GEM_THRESHOLD
      let "outdated_count = \$(bundle outdated --parseable | wc -l) - 1"
      if [ \$outdated_count -le \$threshold ]; then
      { echo "Success. The app only has \$outdated_count outdated gems (must have \$threshold or less)."; } 2> /dev/null
      else
      { echo "Failure.  \$outdated_count gems in the app's Gemfile.lock have newer versions (must have \$threshold or less)."; } 2> /dev/null
      { exit 1; } 2> /dev/null
      fi
    """)
  }

  // @param args [optional, Map]
  // @option args.repo [optional, String] additional brakeman flags
  def brakeman(args=[:]) {
    def cmd = [
      'brakeman-pro',
      '-o brakeman-output.tabs -o brakeman-output.pro --no-progress'
    ]

    if (args.flags) {
      cmd << args.flags
    }

    if (success(cmd.join(' '))) {
      script.echo 'No errors or warnings from Brakeman, Awesome!'
    } else {
      def errors = json('jq -r .errors brakeman-output.pro')

      if (errors) {
        script.currentBuild.result = 'FAILURE'
        script.error("Brakeman Errors: $errors")
      } else {
        script.echo 'No Brakeman ERRORS'
      }
    }

    script.publishBrakeman 'brakeman-output.tabs'

    script.sh([
      'python /var/python_scripts/encrypt_brakeman.py',
      '-f brakeman-output.tabs -j "$WORKSPACE/ISD/" -n $JOB_BASE_NAME'
    ].join(' '))

    script.step([
      $class: 'S3BucketPublisher',
      consoleLogLevel: 'INFO',    // new field
      pluginFailureResultConstraint: 'FAILURE',    // new field
      entries: [[
        sourceFile: 'infosec/*.*',
        bucket: 'infosec/vuln-results/brakeman/brakeman-sa',
        selectedRegion: 'us-east-1',
        noUploadOnFailure: true,
        managedArtifacts: false,
        flatten: true,
        showDirectlyInBrowser: false,
        keepForever: false,
        useServerSideEncryption: true
      ]],
      profileName: 'brakeman-sa',
      dontWaitForConcurrentBuildCompletion: false,
    ])
  }

  // @param args [optional, Map]
  // @option args.railsEnv  [optional, String]
  // @option args.webpacker [optional, bool] false will skip webpacker
  def assetsPrecompile(args=[:]) {
    def railsEnv = args.railsEnv ?: 'production'

    def cmd = ["RAILS_ENV=$railsEnv"]

    if (args.webpacker == false) {
      cmd << 'WEBPACKER_PRECOMPILE=false'
    }

    cmd << 'bundle exec rake assets:precompile'

    script.sh(cmd.join(' '))
  }
}
