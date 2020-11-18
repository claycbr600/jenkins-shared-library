package com.mycomp.foo

class GitHub extends Util {
  def final GITHUB_HOST = 'https://github.com'
  def final TEAMS_BUILD_URL = 'TEAMS_WEBHOOK_URL'
  def final TEAMS_PROD_URL = 'PRODUCTION_TEAMS_WEBHOOK_URL'

  // @return [String] SHA of HEAD commit
  def revision() {
    script.sh(
      script: 'git rev-parse HEAD',
      returnStdout: true
    ).trim()
  }

  // post build status to github. context param is referenced by
  // checkCommit script in vars dir
  // @param args [required, Map]
  // @option args.repo        [required, String] repository name
  // @option args.state       [required, String] build state
  // @option args.description [required, String]
  // @option args.context     [required, String] build context
  def post(args) {
    def sha = revision()
    def description = args.description ?: "Build ${args.state}"

    if (args.context == 'production') {
      args.context = 'staging'
    }

    def context = args.context ? "jenkins/${args.context}" : "jenkins-ci"

    script.echo "git_commit: $sha"

    def cmd = [
      'http --ignore-stdin POST',
      "$GITHUB_HOST/api/v3/repos/${args.repo}/statuses/$sha",
      "'Authorization:token ${script.env.GITHUB_CREDENTIALS}'",
      "state='${args.state}'",
      "target_url='${script.env.BUILD_URL}'",
      "description='$description'",
      "context='$context'"
    ]

    script.sh(cmd.join(' '))
  }

  // post PR comment
  // @param args [required, Map]
  // @option args.repo    [required, String] repository name
  // @option args.comment [required, String]
  def postPullRequestComment(args) {
    def prFile = 'pr.json'

    if (success("[[ ! -f $prFile ]]")){
      def sha = revision()
      def commitUrl = [
        GITHUB_HOST,
        'api/v3/repos',
        args.repo,
        'commits',
        sha,
        'pulls'
      ].join('/')

      def curlScript = [
        "curl -o $prFile --silent $commitUrl",
        "--header 'Authorization: token ${script.env.GITHUB_CREDENTIALS}'",
        "--header 'Content-Type: application/json'",
        "--header 'Accept: application/vnd.github.groot-preview+json'"
      ]

      script.sh(curlScript.join(' '))
    }

    def prNumber = shellCmd("jq -r '.[0].number' $prFile")

    if (prNumber == 'null') return

    def prUrl = "$GITHUB_HOST/${args.repo}/pulls/$prNumber/reviews"

    def cmd = [
      "curl --silent $prUrl",
      "--header 'Authorization: token ${script.env.GITHUB_CREDENTIALS}'",
      "--header 'Content-Type: application/json'",
      "--data '{\"body\": \"${args.comment}\", \"event\": \"COMMENT\"}'"
    ]

    script.sh(cmd.join(' '))
  }

  // MS Teams notification
  // @param args [required, Map]
  // @option args.env   [required, String] pcf environment
  // @option args.state [required, String] build status
  // @option args.color [optional, String] color in hex
  def notifyTeams(args) {
    if (!args.env) return

    def state = args.state.capitalize()
    def jobName = script.env.JOB_NAME
    def buildUrl = script.env.BUILD_URL
    def duration = script.currentBuild.durationString.minus(' and counting')
    def color = args.color ?: hookColor(args.state)

    def msg = [
      "Build $state: $jobName ${args.env} - $buildUrl",
      "Pipeline duration: $duration"
    ].join('<br>')

    hook(args.env) {
      script.office365ConnectorSend(
        status: state,
        webhookUrl: it,
        color: color,
        message: msg
      )
    }
  }

  def hookColor(state) {
    switch (state) {
      case 'success':
        return "#00ff00"
      case 'failure':
        return "#f32013"
      case 'aborted':
        return "#ffae42"
      default:
        script.echo("No Teams Hook Color provided for $state")
        return "#ffff00"
    }
  }

  // hook will set the correct MS Teams webhook url based on environment
  // @param env     [required, String] pcf environment
  // @param webhook [required, Closure] method to execute
  def hook(env, webhook) {
    def hookPost = hookUrl(env)

    script.withCredentials([script.string(
      credentialsId: hookPost,
      variable: 'TEAMS_HOOK'
    )]) {
      webhook(script.TEAMS_HOOK)
    }
  }

  // @param env [required, String] pcf environment
  // @return [String] MS Teams webhook url
  def hookUrl(env) {
    switch (env) {
      case 'preview':
      case 'staging':
        return TEAMS_BUILD_URL
      case 'production':
        return TEAMS_PROD_URL
      default:
        script.echo("No TEAMS HOOK provided for $env, skipping")
    }
  }

  // @param  args [required, Map]
  // @option args.repo    [required, String] repository name
  // @option args.context [required, String] env of previous build
  // @return [bool] true if a successful build has not yet
  //    been run on the current commit
  def checkCommit(args) {
    if (args.context == 'production') {
      args.context = 'staging'
    }

    script.env.GHE_PR_COMMENTER_TOKEN = script.env.GITHUB_CREDENTIALS

    def context = args.context ? "jenkins/${args.context}" : "jenkins-ci"
    def cmd = "check_commit --repo ${args.repo} --commit \$GIT_COMMIT --context $context"
    def status = script.sh(script: cmd, returnStatus: true)

    status != 0
  }
}
