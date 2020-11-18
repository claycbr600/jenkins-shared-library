import com.homeaway.devtools.jenkins.testing.JenkinsPipelineSpecification
import com.mycomp.foo.Ruby

public class RubySpec extends JenkinsPipelineSpecification {
  def ruby = null

	def setup() {
    def script = loadPipelineScriptForTest('resources/Jenkinsfile')
    ruby = new Ruby(script: script)
    explicitlyMockPipelineStep('publishBrakeman')
    explicitlyMockPipelineStep('readJSON')
    script.getBinding().setVariable("currentBuild", [:])
    script.getBinding().setVariable("env", [:])
	}

  // rvmInstall
	def 'install rvm'() {
    given:
      def cmd = [
        'source ~/.rvm/scripts/rvm',
        'rvm use --install --create .',
        'export > rvm.env'
      ].join(' && ')
      def sed = "sed -i '/library.jenkins-ecs-pipeline.version/d' rvm.env"
		when:
      ruby.rvmInstall()
		then:
			1 * getPipelineMock('sh')("bash -c '$cmd'")
			1 * getPipelineMock('sh')(sed)
      1 * getPipelineMock('readFile')([
        file: 'rvm.env'
      ]) >> 'declare -x SHLVL="2"'
    expect:
      ruby.script.env.SHLVL == '2'
	}

  // gemUpdate
	def 'gem update'() {
		when:
      ruby.gemUpdate()
		then:
			1 * getPipelineMock('sh')('gem update --system $RUBY_GEMS_VERSION')
	}

  def 'rubocop install'()  {
    given:
      def cmd = """gem list --silent -i rubocop_rails -v \$RUBOCOP_RAILS_VERSION\
      || gem install rubocop_rails -v=\$RUBOCOP_RAILS_VERSION\
      --no-document"""
    when:
      ruby.rubocopInstall()
    then:
      1 * getPipelineMock('sh')(cmd)
  }

  // bundleInstall
  def 'bundle install'() {
    when:
      ruby.bundleInstall()
    then:
      1 * getPipelineMock('sh')([
        'gem install bundler -v $BUNDLER_VERSION --no-document',
        'bundle install --jobs=4 --retry=4'
      ].join('\n'))
  }

  // brakemanInstall
  def 'brakeman install'() {
    given:
      def reinstallBrakeman = [
        "find /usr/lib/ruby/gems/2.3.0 -name brakeman_pro_install",
        "-mtime -1 -type f -maxdepth 1 -exec false {} +"
      ].join(' ')

      def installBrakeman = [
        'gem install brakeman-pro --no-document --source',
        'https://$BRAKEMAN_ID:$BRAKEMAN_PWD@brakemanpro.com/gems/ -f'
      ].join(' ')
    // already installed
    when:
      ruby.brakemanInstall()
    then:
      1 * getPipelineMock('sh')([
        script: 'gem env home', returnStdout: true
      ]) >> '/usr/lib/ruby/gems/2.3.0'
      1 * getPipelineMock('sh')([
        script: reinstallBrakeman,
        returnStatus: true
      ]) >> 1
    // not installed within last day
    when:
      ruby.brakemanInstall()
    then:
      1 * getPipelineMock('sh')([
        script: 'gem env home', returnStdout: true
      ]) >> '/usr/lib/ruby/gems/2.3.0'
      1 * getPipelineMock('sh')([
        script: reinstallBrakeman,
        returnStatus: true
      ]) >> 0
      1 * getPipelineMock('sh')([
        installBrakeman,
        "touch /usr/lib/ruby/gems/2.3.0/brakeman_pro_install"
      ].join('\n'))
  }

  // dbRebuild
  def 'dbRebuild with defaults'() {
    when:
      ruby.dbRebuild()
    then:
      1 * getPipelineMock('sh')(
        'RAILS_ENV=test bundle exec rake db:drop db:create db:migrate'
      )
  }

  def 'dbRebuild with params'() {
    when:
      ruby.dbRebuild([railsEnv: 'staging', disableDBEnvCheck: true])
    then:
      1 * getPipelineMock('sh')([
        'RAILS_ENV=staging DISABLE_DATABASE_ENVIRONMENT_CHECK=1',
        'bundle exec rake db:drop db:create db:migrate'
      ].join(' '))
  }

  // webpack
  def 'webpack with defaults'() {
    when:
      ruby.webpack()
    then:
      1 * getPipelineMock('sh')([
        'node --version',
        "export NODE_OPTIONS='--max-old-space-size=2048'",
        'rm -rf public/packs*',
        "NODE_ENV=production bin/webpack"
      ].join('\n'))
  }

  def 'webpack with params'() {
    when:
      ruby.webpack(railsEnv: 'staging', nodeEnv: 'staging', maxSize: 1024)
    then:
      1 * getPipelineMock('sh')([
        'node --version',
        "export NODE_OPTIONS='--max-old-space-size=1024'",
        'rm -rf public/packs*',
        "RAILS_ENV=staging NODE_ENV=staging bin/webpack"
      ].join('\n'))
  }

  // rspec
  def 'rspec'() {
    when:
      ruby.rspec()
    then:
      1 * getPipelineMock('sh')(
        'SIMPLECOV_FORMATTER=rcov bundle exec rspec -f d --color --tty spec --fail-fast'
      )
  }

  // coverageRspec
  def 'coverageRspec'() {
    given:
      ruby.script.getBinding().setVariable("env", [
        GITHUB_CREDENTIALS: 'abc123'
      ])
      def prUrl = "https://github.com/org/myrepo/pulls/1234/reviews"
      def cmd = [
        "curl --silent $prUrl",
        "--header 'Authorization: token abc123'",
        "--header 'Content-Type: application/json'",
        "--data '{\"body\": \"RSpec coverage: **95%**\", \"event\": \"COMMENT\"}'"
      ]
    when:
      ruby.coverageRspec(repo: 'org/myrepo')
    then:
      1 * getPipelineMock('sh')([
        script: "jq -r '.result.covered_percent' coverage/.last_run.json",
        returnStdout: true
      ]) >> '95'
      1 * getPipelineMock('sh')([
        script: '[[ ! -f pr.json ]]',
        returnStatus: true
      ]) >> 1
      1 * getPipelineMock('sh')([
        script: "jq -r '.[0].number' pr.json",
        returnStdout: true
      ]) >> '1234'
      1 * getPipelineMock('sh')(cmd.join(' '))
  }

  // rcov
  def 'rcov'() {
    // check defaults
    when:
      ruby.rcov()
    then:
      1 * getPipelineMock('step')([
        $class: 'RcovPublisher',
        reportDir: 'coverage/rcov',
        targets: [
          [metric: 'TOTAL_COVERAGE', healthy: 95, unhealthy: 92, unstable: 90],
          [metric: 'CODE_COVERAGE', healthy: 95, unhealthy: 92, unstable: 90]
        ]
      ])
    // check overrides
    when:
      ruby.rcov(totalCoverage: [healthy: 90, unhealthy: 80, unstable: 0])
    then:
      1 * getPipelineMock('step')([
        $class: 'RcovPublisher',
        reportDir: 'coverage/rcov',
        targets: [
          [metric: 'TOTAL_COVERAGE', healthy: 90, unhealthy: 80, unstable: 0],
          [metric: 'CODE_COVERAGE', healthy: 95, unhealthy: 92, unstable: 90]
        ]
      ])
    // check overrides with missing field
    when:
      ruby.rcov(totalCoverage: [healthy: 90, unstable: 0])
    then:
      1 * getPipelineMock('step')([
        $class: 'RcovPublisher',
        reportDir: 'coverage/rcov',
        targets: [
          [metric: 'TOTAL_COVERAGE', healthy: 90, unhealthy: 92, unstable: 0],
          [metric: 'CODE_COVERAGE', healthy: 95, unhealthy: 92, unstable: 90]
        ]
      ])
  }


  // ruboViolationCount
  def 'ruboViolationCount'() {
    given:
      ruby.script.getBinding().setVariable("env", [
        GITHUB_CREDENTIALS: 'abc123',
        RUBOCOP_THRESHOLD: 25
      ])
      def prUrl = "https://github.com/org/myrepo/pulls/1234/reviews"
      def cmd = [
        "curl --silent $prUrl",
        "--header 'Authorization: token abc123'",
        "--header 'Content-Type: application/json'",
        "--data '{\"body\": \"Rubocop violations: **20/25**\", \"event\": \"COMMENT\"}'"
      ]
    when:
      ruby.ruboViolationCount(repo: 'org/myrepo')
    then:
      1 * getPipelineMock('sh')([
        script: "cat rubocop/total-violations-count.txt",
        returnStdout: true
      ]) >> '20'
      1 * getPipelineMock('sh')([
        script: '[[ ! -f pr.json ]]',
        returnStatus: true
      ]) >> 1
      1 * getPipelineMock('sh')([
        script: "jq -r '.[0].number' pr.json",
        returnStdout: true
      ]) >> '1234'
      1 * getPipelineMock('sh')(cmd.join(' '))
  }

  // rubo
  // gemCheck

  // brakeman
  def 'brakeman'() {
    given:
      def cmd = [
        'brakeman-pro',
        '-o brakeman-output.tabs -o brakeman-output.pro --no-progress'
      ]
      def errors = [
        [error: "message", location: "file"]
      ]
    // no flags, success
    when:
      ruby.brakeman()
    then:
      1 * getPipelineMock('sh')([
        script: cmd.join(' '),
        returnStatus: true
      ]) >> 0
      1 * getPipelineMock('echo')('No errors or warnings from Brakeman, Awesome!')
    // with flags, fail, publish
    when:
      ruby.brakeman(flags: '--except UnscopedFind')
    then:
      1 * getPipelineMock('sh')([
        script: (cmd + ['--except UnscopedFind']).join(' '),
        returnStatus: true
      ]) >> 1
      1 * getPipelineMock('sh')([
        script: 'jq -r .errors brakeman-output.pro',
        returnStdout: true
      ]) >> '{"errors":[{"error":"message","location":"file"}]}'
      1 * getPipelineMock('readJSON')([
        text: '{"errors":[{"error":"message","location":"file"}]}'
      ]) >> errors
      1 * getPipelineMock('publishBrakeman')('brakeman-output.tabs')
      1 * getPipelineMock('sh')([
        'python /var/python_scripts/encrypt_brakeman.py',
        '-f brakeman-output.tabs -j "$WORKSPACE/ISD/" -n $JOB_BASE_NAME'
      ].join(' '))
  }

  // assetsPrecompile
  def 'assetsPrecompile'() {
    when:
      ruby.assetsPrecompile()
    then:
      1 * getPipelineMock('sh')(
        'RAILS_ENV=production bundle exec rake assets:precompile'
      )
    when:
      ruby.assetsPrecompile(railsEnv: 'staging', webpacker: false)
    then:
      1 * getPipelineMock('sh')([
        'RAILS_ENV=staging WEBPACKER_PRECOMPILE=false',
        'bundle exec rake assets:precompile'
      ].join(' '))
  }
}
