import com.homeaway.devtools.jenkins.testing.JenkinsPipelineSpecification
import com.mycomp.foo.Util

public class UtilSpec extends JenkinsPipelineSpecification {
  def util = null

	def setup() {
    def script = loadPipelineScriptForTest('resources/Jenkinsfile')
    util = new Util(script: script)
    explicitlyMockPipelineStep('readJSON')
	}

  // success
	def 'test status code 0'() {
		when:
      def success = util.success('ls')
		then:
			1 * getPipelineMock('sh')([
        script: 'ls',
        returnStatus: true
      ]) >> 0
    expect:
      success
	}

  // success - failure
	def 'test status code 1'() {
		when:
      def success = util.success('ls blah')
		then:
			1 * getPipelineMock('sh')([
        script: 'ls blah',
        returnStatus: true
      ]) >> 1
    expect:
      !success
	}

  // shellCmd
  def 'shellCmd output'() {
    when:
      def output = util.shellCmd('pwd')
    then:
			1 * getPipelineMock('sh')([
        script: 'pwd',
        returnStdout: true
      ]) >> 'tmp\n'
    expect:
      output == 'tmp'
  }

  // json
  def 'json output'() {
    when:
      util.json('cf curl /v2/apps/1234/stats')
    then:
			1 * getPipelineMock('sh')([
        script: 'cf curl /v2/apps/1234/stats',
        returnStdout: true
      ]) >> '{"0": {"state":"RUNNING"}}'

      1 * getPipelineMock('readJSON')([text: '{"0": {"state":"RUNNING"}}'])
  }

  // json - empty string
  def 'json output empty string'() {
    when:
      util.json('cf curl /v2/apps/1234/stats')
    then:
			1 * getPipelineMock('sh')([
        script: 'cf curl /v2/apps/1234/stats',
        returnStdout: true
      ]) >> ''

      1 * getPipelineMock('readJSON')([text: '{}'])
  }

  // json - null string
  def 'json output null string'() {
    when:
      util.json('cf curl /v2/apps/1234/stats')
    then:
			1 * getPipelineMock('sh')([
        script: 'cf curl /v2/apps/1234/stats',
        returnStdout: true
      ]) >> 'null'

      1 * getPipelineMock('readJSON')([text: '{}'])
  }
}
