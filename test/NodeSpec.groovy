import com.homeaway.devtools.jenkins.testing.JenkinsPipelineSpecification
import com.mycomp.foo.Node

public class NodeSpec extends JenkinsPipelineSpecification {
  def node = null

	def setup() {
    def script = loadPipelineScriptForTest('resources/Jenkinsfile')
    node = new Node(script: script)
	}

  // yarnInstall
	def 'yarn install'() {
    given:
      def nexus = 'https://nexus.com'
		when:
      node.yarnInstall()
		then:
			1 * getPipelineMock('sh')([
        "yarn config set registry '$nexus/nexus/repository/npm-group/'",
        'yarn config set cafile "/etc/ssl/certs/ca-certificates.crt"',
        'yarn install'
      ].join('\n'))
	}

  // node test - run
  def 'run node test'() {
    when:
      node.test(['ci:test', 'ci:static'])
    then:
      1 * getPipelineMock('sh')('yarn run ci:test')
      1 * getPipelineMock('sh')('yarn run ci:static')
  }
}
