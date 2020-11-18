package com.mycomp.foo

class Node extends Util {
  def final nexus = 'https://nexus.com'
  def final nvmInstallURL = [
    'https://github.com/nvm-sh/nvm/blob/master/install.sh',
    'token=AAAMmTsq-4kTxIh-AOQxHttz05M_oQswks5cbJ6rwA%3D%3D'
  ].join('?')

  // install node version
  def version(nodeVersion) {
    def pathArray = shellCmd('echo $PATH').split(':')
    def rvmPath = pathArray.findAll { it.contains('rvm') }

    script.nvm(
      nvmInstallURL: nvmInstallURL,
      nvmIoJsOrgMirror: 'https://iojs.org/dist',
      nvmNodeJsOrgMirror: 'https://nodejs.org/dist',
      version: nodeVersion
    ) {
      script.env['NVM_BIN'] = shellCmd('echo $NVM_BIN')
      rvmPath << shellCmd('echo $PATH')
      script.env['PATH'] = rvmPath.join(':')
    }

    script.sh('echo "node version: `node -v`"')
  }

  // install yarn
  def yarnInstall() {
    def cmd = [
      "yarn config set registry '$nexus/nexus/repository/npm-group/'",
      'yarn config set cafile "/etc/ssl/certs/ca-certificates.crt"',
      'yarn install'
    ]

    script.sh(cmd.join('\n'))
  }

  def test(specs) {
    specs.each { script.sh("yarn run $it") }
  }
}
