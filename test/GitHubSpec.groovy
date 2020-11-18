import com.homeaway.devtools.jenkins.testing.JenkinsPipelineSpecification
import com.mycomp.foo.GitHub

public class GitHubSpec extends JenkinsPipelineSpecification {
  String githubHost = 'https://github.com'
  def github = null

	def setup() {
    def script = loadPipelineScriptForTest("resources/Jenkinsfile")
    github = new GitHub(script: script)
    explicitlyMockPipelineStep('withCredentials')
    explicitlyMockPipelineStep('office365ConnectorSend')
	}

  // revision
	def "get current git revision"() {
		when:
      github.revision()
		then:
			1 * getPipelineMock("sh")([
        script: "git rev-parse HEAD",
        returnStdout: true
      ]) >> ""
	}

  // post
  def "git post build status"() {
    given:
      github.script.getBinding().setVariable("env", [
        GITHUB_CREDENTIALS: 'abc123',
        BUILD_URL: 'http://build_url.com'
      ])
      def cmd = [
        'http --ignore-stdin POST',
        "$githubHost/api/v3/repos/org/myapp/statuses/12345",
        "'Authorization:token abc123'",
        "state='pending'",
        "target_url='http://build_url.com'",
        "description='Build pending'",
        "context='jenkins/preview'"
      ]
		when:
      github.post repo: 'org/myapp', state: 'pending', context: 'preview'
		then:
			1 * getPipelineMock("sh")([
        script: "git rev-parse HEAD",
        returnStdout: true
      ]) >> "12345"
      1 * getPipelineMock("echo")("git_commit: 12345")
      1 * getPipelineMock("sh")(cmd.join(' '))
  }

  // postPullRequestComment
  def "post pull request comment"() {
    given:
      def prFile  = "pr.json"
      def commitUrl = "$githubHost/api/v3/repos/org/myapp/commits/9999/pulls"

      github.script.getBinding().setVariable("env", [
        GITHUB_CREDENTIALS: 'abc123',
        BUILD_URL: 'http://build_url.com'
      ])

      def curlScript = [
        "curl -o $prFile --silent $commitUrl",
        "--header 'Authorization: token abc123'",
        "--header 'Content-Type: application/json'",
        "--header 'Accept: application/vnd.github.groot-preview+json'"
      ].join(' ')

      def prUrl= "$githubHost/org/myapp/pulls/123/reviews"

      def cmd = [
        "curl --silent $prUrl",
        "--header 'Authorization: token abc123'",
        "--header 'Content-Type: application/json'",
        "--data '{\"body\": \"hello world\", \"event\": \"COMMENT\"}'"
      ].join(' ')

    when: "PR file does not exist"
      github.postPullRequestComment repo: 'org/myapp', comment:  "hello world"
    then:
      1 * getPipelineMock("sh")([
        script: "[[ ! -f $prFile ]]",
        returnStatus: true
      ]) >> 0

			1 * getPipelineMock("sh")([
        script: "git rev-parse HEAD",
        returnStdout: true
      ]) >> "9999"

      1 * getPipelineMock("sh")(curlScript)

      1 * getPipelineMock("sh")([
        script: "jq -r '.[0].number' $prFile",
        returnStdout: true
      ]) >>  "123"

      1 * getPipelineMock("sh")(cmd)

    when: "PR file exists"
      github.postPullRequestComment repo: 'org/myapp', comment:  "hello world"
    then:
      1 * getPipelineMock("sh")([
        script: "[[ ! -f $prFile ]]",
        returnStatus: true
      ]) >> 1

      0 * getPipelineMock("sh")(curlScript)

      1 * getPipelineMock("sh")([
        script: "jq -r '.[0].number' $prFile",
        returnStdout: true
      ]) >> "null"

      0 * getPipelineMock("sh")(cmd)
  }

  // notify
  def "notifyTeams"() {
		when:
      github.notifyTeams state: 'unstable'
		then:
			0 * getPipelineMock("echo")("No Teams Hook Color provided for unstable")
  }

  def "notifyTeams success"() {
    given:
      def binding = github.script.getBinding()
      def msg = [
        "Build Success: myapp-deploy preview - http://build.com/job/myapp-deploy/123",
        "Pipeline duration: 15 min"
      ].join('<br>')

      binding.setVariable("currentBuild", [
        durationString: '15 min and counting'
      ])
      binding.setVariable("env", [
        JOB_NAME: 'myapp-deploy',
        BUILD_URL: 'http://build.com/job/myapp-deploy/123'
      ])
		when:
      github.notifyTeams state: 'success', env: 'preview'
		then:
      1 * getPipelineMock('withCredentials')(_) >> {
        github.script.TEAMS_HOOK = 'https://outlook.office.com/webhook'
      }
      1 * getPipelineMock("office365ConnectorSend")([
        status: 'Success',
        webhookUrl: 'https://outlook.office.com/webhook',
        color: "#00ff00",
        message: msg
      ])
  }
}
