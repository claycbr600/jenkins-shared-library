package com.mycomp.foo

class Util implements Serializable {
  def script

  // @param str [required, String] shell command
  // @return [boolean] true if shell command exits with code 0
  def success(str) {
    script.sh(script: str, returnStatus: true) == 0
  }

  // @param str [required, String] shell command
  // @return [String] output of shell command
  def shellCmd(str) {
    script.sh(script: str, returnStdout: true).trim()
  }

  // @param str [required, String] shell command
  // @return [Map] json output of shell command
  def json(str) {
    def output = shellCmd(str)

    // check for null
    if (output == 'null' || output == '') {
      output = '{}'
    }

    script.readJSON(text: output)
  }
}
