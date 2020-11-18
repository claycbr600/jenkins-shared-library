import com.homeaway.devtools.jenkins.testing.JenkinsPipelineSpecification
import com.mycomp.foo.Pcf

public class PcfSpec extends JenkinsPipelineSpecification {
  def script = null
  def pcf = null

	def setup() {
    script = loadPipelineScriptForTest('resources/Jenkinsfile')
    pcf = new Pcf(script: script)
    explicitlyMockPipelineStep('usernamePassword')
    explicitlyMockPipelineStep('withCredentials')
    explicitlyMockPipelineStep('readYaml')
	}

  // loginParams
	def 'login params - preview'() {
    given:
      def non_prod_url = 'https://api.system.pcf-test.com'
      def jenkins_non_prod_user = 'jenkins-pcf-non-prod-user'
		when:
      def params = pcf.loginParams('preview')
    then:
      params == [non_prod_url, 'myorg', jenkins_non_prod_user]
	}

	def 'login params - staging'() {
    given:
      def prod_url = 'https://api.system.pcf.com'
      def jenkins_prod_user = 'jenkins-pcf-user'
		when:
      def params = pcf.loginParams('staging')
    then:
      params == [prod_url, 'myorg Staging', jenkins_prod_user]
	}

	def 'login params - production'() {
    given:
      def prod_url = 'https://api.system.pcf.com'
      def jenkins_prod_user = 'jenkins-pcf-user'
		when:
      def params = pcf.loginParams('production')
    then:
      params == [prod_url, 'myorg Production', jenkins_prod_user]
	}

  // login
	def 'login - preview'() {
    given:
      def url = 'https://api.system.pcf-test.com'
		when:
      pcf.login(app: 'myapp', env: 'preview')
    then:
      1 * getPipelineMock('usernamePassword')([
        credentialsId: 'jenkins-pcf-non-prod-user',
        usernameVariable: 'PCF_USER',
        passwordVariable: 'PCF_PASSWORD'
      ])
      1 * getPipelineMock('withCredentials')(_)
      1 * getPipelineMock('sh')([
        "cf login -a $url",
        "-u \$PCF_USER -p \$PCF_PASSWORD",
        "-o 'myorg' -s myapp-preview --skip-ssl-validation"
      ].join(' '))
	}

	def 'login - staging'() {
    given:
      def url = 'https://api.system.pcf.com'
		when:
      pcf.login(app: 'myapp', env: 'staging')
    then:
      1 * getPipelineMock('usernamePassword')([
        credentialsId: 'jenkins-pcf-user',
        usernameVariable: 'PCF_USER',
        passwordVariable: 'PCF_PASSWORD'
      ])
      1 * getPipelineMock('withCredentials')(_)
      1 * getPipelineMock('sh')([
        "cf login -a $url",
        "-u \$PCF_USER -p \$PCF_PASSWORD",
        "-o 'myorg Staging' -s myapp-staging --skip-ssl-validation"
      ].join(' '))
	}

	def 'login - production'() {
    given:
      def url = 'https://api.system.pcf.com'
		when:
      pcf.login(app: 'myapp', env: 'production')
    then:
      1 * getPipelineMock('usernamePassword')([
        credentialsId: 'jenkins-pcf-user',
        usernameVariable: 'PCF_USER',
        passwordVariable: 'PCF_PASSWORD'
      ])
      1 * getPipelineMock('withCredentials')(_)
      1 * getPipelineMock('sh')([
        "cf login -a $url",
        "-u \$PCF_USER -p \$PCF_PASSWORD",
        "-o 'myorg Production' -s myapp-production --skip-ssl-validation"
      ].join(' '))
	}

  // logout
  def 'logout'() {
    when:
      pcf.logout()
    then:
      1 * getPipelineMock('sh')('cf logout')
  }

  // createNFS
  def 'createNFS - service exists'() {
    when:
      pcf.createNFS(mode: 'public', mount: 'myapp', env: 'preview')
    then:
      1 * getPipelineMock('sh')([
        script: 'cf service public-nfs',
        returnStatus: true
      ]) >> 0
      1 * getPipelineMock('echo')('[PCF]: public-nfs exists. Not creating.')
  }

  def 'createNFS - service does not exists'() {
    given:
      script.getBinding().setVariable("NFS_SERVER", "1.2.3.4")
    when:
      pcf.createNFS(mode: 'public', mount: 'myapp', env: 'preview')
    then:
      1 * getPipelineMock('sh')([
        script: 'cf service public-nfs',
        returnStatus: true
      ]) >> 1
      0 * getPipelineMock('echo')('[PCF]: public-nfs exists. Not creating.')
      1 * getPipelineMock('echo')('[PCF]: public-nfs does not exist. Creating.')
      1 * getPipelineMock('string.call')([
        credentialsId: 'NFS_Preview_IP',
        variable: 'NFS_SERVER'
      ])
      1 * getPipelineMock('withCredentials')(_)
      1 * getPipelineMock('sh')([
        "cf create-service nfs Existing public-nfs -c",
        "'{\"share\":\"1.2.3.4/exports/myapp/public\"}'"
      ].join(' '))
  }

  // nfs
  def 'nfs - staging'() {
    when:
      def nfsCreds = pcf.nfs('staging')
    then:
      nfsCreds == 'NFS_Staging_IP'
  }

  def 'nfs - env not found'() {
    when:
      def nfsCreds = pcf.nfs('blah')
    then:
      1 * getPipelineMock('error')('No NFS Server found for blah')
  }

  // bindNFS
  def 'bind nfs'() {
    when:
      pcf.bindNFS(app: 'myapp', mode: 'public')
    then:
      1 * getPipelineMock('sh')([
        "cf bind-service myapp public-nfs -c",
        "'{\"uid\":\"65534\",\"gid\":\"65534\",\"mount\":\"/var/nfs-share/public\"}'"
      ].join(' '))
  }

  // pushNoStart
  def 'pushNoStart'() {
    when:
      pcf.pushNoStart(app: 'myapp', file: 'manifest.preview.yml')
    then:
      1 * getPipelineMock('sh')([
        "cf zero-downtime-push myapp",
        "-f manifest.preview.yml",
        "--legacy-push --no-start"
      ].join(' '))
  }

  // zeroDowntimePush
  def 'zeroDowntimePush'() {
    when:
      pcf.zeroDowntimePush(app: 'myapp', file: 'manifest.preview.yml')
    then:
      1 * getPipelineMock('sh')([
        "cf zero-downtime-push myapp",
        "-f manifest.preview.yml",
        "--legacy-push --route-only --show-crash-log"
      ].join(' '))
  }

  // resetDB - postgres service does not exist
  def 'resetDB no postgres service'() {
    when:
      pcf.resetDB([])
    then:
      1 * getPipelineMock('sh')([
        script: "cf services | grep postgres",
        returnStatus: true
      ]) >> 1
      1 * getPipelineMock('echo')([
        'postgres service does not exist.',
        'skipping reset'
      ].join(' '))
  }

  // resetDB - postgres service exists
  def 'resetDB with postgres service'() {
    when:
      pcf.resetDB([])
    then:
      1 * getPipelineMock('sh')([
        script: "cf services | grep postgres",
        returnStatus: true
      ]) >> 0
      0 * getPipelineMock('echo')([
        'postgres service does not exist.',
        'skipping reset'
      ].join(' '))
      1 * getPipelineMock('echo')([
        'postgres instance is not bound to an app.',
        'skipping reset'
      ].join(' '))
  }

  // resetDB - postgres service exists with apps
  def 'resetDB with postgres service and apps'() {
    given:
      def cmd = [
        "cf curl /v2/apps/123456/service_bindings",
        "jq -r '.resources[].entity.credentials.uri | select(. != null)'",
        "grep -q '^postgres'"
      ].join(' | ')
    // postgres not bound to an app
    when:
      pcf.resetDB([[name: 'myapp']])
    then:
      1 * getPipelineMock('sh')([
        script: "cf services | grep postgres",
        returnStatus: true
      ]) >> 0
      1 * getPipelineMock('sh')([
        script: 'cf app myapp --guid',
        returnStdout: true
      ]) >> '123456'
      1 * getPipelineMock('sh')([
        script: cmd,
        returnStatus: true
      ]) >> 1
      1 * getPipelineMock('echo')([
        'postgres instance is not bound to an app.',
        'skipping reset'
      ].join(' '))
    // postgres bound to app
    when:
      pcf.resetDB([[name: 'myapp']])
    then:
      1 * getPipelineMock('sh')([
        script: "cf services | grep postgres",
        returnStatus: true
      ]) >> 0
      1 * getPipelineMock('sh')([
        script: 'cf app myapp --guid',
        returnStdout: true
      ]) >> '123456'
      1 * getPipelineMock('sh')([
        script: cmd,
        returnStatus: true
      ]) >> 0
      1 * getPipelineMock('echo')('resetting db')
      1 * getPipelineMock('sh')([
        "cf run-task myapp 'bundle exec rake db:migrate VERSION=0'",
        "--name reset-db"
      ].join(' '))
  }

  // deploy
  def 'deploy env not set'() {
    when:
      pcf.deploy([file: 'myfile.txt'])
    then:
      1 * getPipelineMock('readYaml')([
        file: 'myfile.txt'
      ]) >> [applications: [[env: [:]]]]
      1 * getPipelineMock('error')("RAILS_ENV not configured in manifest")
  }

  def 'deploy full'() {
    when:
      pcf.deploy([file: 'myfile.txt'])
    then:
      1 * getPipelineMock('readYaml')([
        file: 'myfile.txt'
      ]) >> [applications: [[name: 'myapp', env: [RAILS_ENV: 'preview']]]]
      1 * getPipelineMock("sh")([
        'cf zero-downtime-push myapp',
        '-f myfile.txt --legacy-push --no-start'
      ].join(' '))
      1 * getPipelineMock("sh")([
        'cf zero-downtime-push myapp',
        '-f myfile.txt --legacy-push --route-only --show-crash-log'
      ].join(' '))
  }

  // afterParty
  // wait
}
