package com.mycomp.foo

class Pcf extends Util {
  // non-prod
  final def PCF_NON_PROD_URL = 'https://api.system.pcf-test.com'
  final def JENKINS_NON_PROD_USER = 'jenkins-pcf-non-prod-user'

  // prod
  final def PCF_PROD_URL = 'https://api.system.pcf.com'
  final def JENKINS_PROD_USER = 'jenkins-pcf-user'

  // nfs
  final def NFS_PREVIEW = 'NFS_Preview_IP'
  final def NFS_STAGING = 'NFS_Staging_IP'
  final def NFS_PROD = 'NFS_Production_IP'

  // @param args [required, Map]
  // @option args.app [required, String] application name
  // @option args.env [required, String] pcf environment
  def login(args) {
    def (url, org, credentialsId) = loginParams(args.env)
    def space = "${args.app}-${args.env}"

    def cmd = [
      "cf login -a $url",
      "-u \$PCF_USER -p \$PCF_PASSWORD",
      "-o '$org' -s $space --skip-ssl-validation"
    ]

    script.withCredentials([script.usernamePassword(
      credentialsId: credentialsId,
      usernameVariable: 'PCF_USER',
      passwordVariable: 'PCF_PASSWORD'
    )]) {
      script.sh(cmd.join(' '))
    }
  }

  // @param env [required, String] pcf environment
  // @return [Array] list of login parameters for specific env
  def loginParams(env) {
    def org = 'myorg'
    def credentialsId, url

    switch (env) {
      case 'preview':
        credentialsId = JENKINS_NON_PROD_USER
        url = PCF_NON_PROD_URL
        break
      case ['staging', 'production']:
        credentialsId = JENKINS_PROD_USER
        url = PCF_PROD_URL
        org += " ${env.capitalize()}"
        break
    }

    [url, org, credentialsId]
  }

  // pcf logout
  def logout() {
    script.sh("cf logout")
  }

  // @param args [required, Map]
  // @option args.env   [required, String] pcf environment
  // @option args.mode  [required, String] NFS mode [public|private]
  // @option args.mount [required, String] mount point
  def createNFS(args) {
    def publicOrPrivate = args.mode
    def mount = args.mount

    // check for nfs service
    if (success("cf service $publicOrPrivate-nfs")) {
      script.echo "[PCF]: $publicOrPrivate-nfs exists. Not creating."
      return
    }

    script.echo "[PCF]: $publicOrPrivate-nfs does not exist. Creating."
    def nfsCreds = nfs(args.env)

    script.withCredentials([script.string(
      credentialsId: nfsCreds,
      variable: 'NFS_SERVER'
    )]) {
      def nfsServer = script.NFS_SERVER
      def cmd = [
        "cf create-service nfs Existing $publicOrPrivate-nfs -c",
        "'{\"share\":\"$nfsServer/exports/$mount/$publicOrPrivate\"}'"
      ]
      script.sh(cmd.join(' '))
    }
  }

  // @option env [required, String] pcf environment
  // @return [String] NFS credentials ID for given env
  def nfs(env) {
    switch (env) {
      case 'preview':
        return NFS_PREVIEW
      case 'staging':
        return NFS_STAGING
      case 'production':
        return NFS_PROD
      default:
        script.error("No NFS Server found for $env")
    }
  }

  // @param args [required, Map]
  // @option args.app  [required, String] application name
  // @option args.mode [required, String] NFS mode [public|private]
  def bindNFS(args) {
    def publicOrPrivate = args.mode
    def appName = args.app
    script.sh([
      "cf bind-service $appName $publicOrPrivate-nfs -c",
      "'{\"uid\":\"65534\",\"gid\":\"65534\",\"mount\":\"/var/nfs-share/$publicOrPrivate\"}'"
    ].join(' '))
  }

  // @param args [required, Map]
  // @option args.app  [required, String] application name
  // @option args.file [required, String] manifest file
  def pushNoStart(args) {
    script.sh([
      "cf zero-downtime-push ${args.app} -f ${args.file}",
      "--legacy-push --no-start"
    ].join(' '))
  }

  // @param args [required, Map]
  // @option args.app  [required, String] application name
  // @option args.file [required, String] manifest file
  def zeroDowntimePush(args) {
    script.sh([
      "cf zero-downtime-push ${args.app} -f ${args.file}",
      "--legacy-push --route-only --show-crash-log"
    ].join(' '))
  }

  // reset database. find first app with postgres service bound and
  // run migration version down to zero
  // @param apps [required, []Map] list of application maps
  def resetDB(apps) {
    if (!success("cf services | grep postgres")) {
      script.echo "postgres service does not exist. skipping reset"
      return
    }

    def guid, cmd
    for (app in apps) {
      if (app.env?.RAILS_ENV == 'production') {
        script.echo "CANNOT reset database in production!!!"
        continue
      }

      guid = shellCmd("cf app ${app.name} --guid")
      cmd = [
        "cf curl /v2/apps/$guid/service_bindings",
        "jq -r '.resources[].entity.credentials.uri | select(. != null)'",
        "grep -q '^postgres'"
      ].join(' | ')

      if (success(cmd)) {
        script.echo "resetting db"
        script.sh([
          "cf run-task ${app.name}",
          "'bundle exec rake db:migrate VERSION=0'",
          "--name reset-db"
        ].join(' '))
        return
      }
    }

    script.echo "postgres instance is not bound to an app. skipping reset"
  }

  // @param args [required, Map]
  // @option args.resetDB [optional, bool]
  // @option args.nfs     [optional, Map] NFS config [mode:,mount:]
  def deploy(args) {
    def manifest = script.readYaml(file: args.file)
    def apps = manifest.applications

    def app = apps.find { it?.env?.RAILS_ENV }
    if (!app) {
      script.error("RAILS_ENV not configured in manifest")
      return
    }

    def pcfEnv = app.env.RAILS_ENV

    // reset db
    if (args.resetDB) {
      resetDB(apps)
    }

    // legacy push
    apps.each { pushNoStart(app: it.name, file: args.file) }

    // create nfs
    if (args.nfs) {
      createNFS(args.nfs + [env: pcfEnv])

      // bind apps
      apps.each { bindNFS(app: it.name, mode: args.nfs.mode) }
    }

    // push routes
    apps.each { zeroDowntimePush(app: it.name, file: args.file) }
  }

  def afterPartyInstalled() {
    success('bundle info after_party &> /dev/null')
  }

  // @param args [required, Map]
  // @option args.file        [required, String] manifest file
  // @option args.delay       [optional, Integer] sleep interval in sec [10]
  // @option args.maxAttempts [optional, Integer]
  //   - maximum attempts to check status update [30]
  def afterParty(args) {
    def manifest = script.readYaml(file: args.file)
    def app = manifest.applications.first()
    def memory = app.memory ?: "1G"
    def diskQuota = app.disk_quota ?: "1G"
    def appName = app.name
    def appGuid = shellCmd("cf app $appName --guid")
    def taskName = "after-party-build-${script.env.BUILD_NUMBER}"

    // wait for app to fully start
    wait(
      cmd: "cf curl v2/apps/$appGuid/stats | jq -r '.[].state'",
      successState: 'RUNNING',
      failureState: 'CRASHED',
      delay: args.delay ?: 10,
      maxAttempts: args.maxAttempts ?: 30,
      msg: 'App start'
    )

    def cmd = [
      "cf run-task $appName 'bundle exec rake after_party:run'",
      "-m $memory -k $diskQuota --name $taskName"
    ]

    script.sh(cmd.join(' '))
    script.echo("after party task for $appName scheduled successfully")

    // wait for run-task to complete
    wait(
      cmd: "cf tasks $appName | grep $taskName | awk '{ print \$3 }'",
      successState: 'SUCCEEDED',
      failureState: 'FAILED',
      delay: args.delay ?: 10,
      maxAttempts: args.maxAttempts ?: 30
    )

    def logs = [ "cf logs $appName --recent | grep -v APP/PROC/ | grep -v STG" ]
    script.sh(logs.join(''))
  }

  // wait loop used for afterParty
  // @param args [required, Map]
  // @option args.cmd         [required, String] shell command
  // @option args.state       [required, String] terminating state
  // @option args.delay       [required, Integer] sleep interval in sec
  // @option args.maxAttempts [required, Integer]
  //   - maximum attempts to check status update
  def wait(args) {
    int delay = args.delay
    int maxAttempts = args.maxAttempts
    def msg = args.msg ?: 'Task'
    def resp, states

    for (int i = 0; i <= maxAttempts; i++) {
      resp = shellCmd(args.cmd)
      states = resp.split('\n')

      // return on failure
      if (states.any { it == args.failureState }) {
        script.error "FAILURE: $msg state: $states"
        return
      }

      // wait for success
      if (states.any { it != args.successState }) {
        script.echo "$i. current state: $states ..."
        script.sleep(time: delay, unit: 'SECONDS')
      } else {
        script.echo "SUCCESS: $msg complete"
        return
      }
    }

    script.error("FAILURE: $msg unsuccessful within ${delay * maxAttempts} sec")
  }

  // @param config [required, Map] pipeline config
  // @return [bool] true if manifest found for background jobs
  def backgroundJobFound(config) {
    ['sidekiq', 'delayedjob'].any {
      script.fileExists("manifests/${it}.${config.deployTo}.yml")
    }
  }

  // @param config [required, Map] pipeline config
  // @return [String] name prefix of background job manifest file
  def backgroundJobName(config) {
    ['sidekiq', 'delayedjob'].find {
      script.fileExists("manifests/${it}.${config.deployTo}.yml")
    }
  }
}
