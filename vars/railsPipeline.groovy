import com.mycomp.foo.*

def call(body) {
  def foo = [
    github: new GitHub(script: this),
    ruby: new Ruby(script: this),
    node: new Node(script: this),
    pcf: new Pcf(script: this),
    db: new Database(script: this)
  ]

  def config = [
    deployEnvs: [],
    keepRevisions: '30',
    pcfEnv: (params.DEPLOY_TO ?: '').split('-').first(),
    dbRebuildFlags: [disableDBEnvCheck: true],
    webpack: true,
    rcovMetrics: [
      totalCoverage: [healthy: 95, unhealthy: 0, unstable: 0],
      codeCoverage: [healthy: 95, unhealthy: 95, unstable: 0]
    ],
    nfs: [:],
    parallelTest: [:],
    // optional closure setup
    setup: null,
    yarnTest: [],
    nodeVersion: ''
  ]

  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  config.deployTo = params.DEPLOY_TO ?: config.pcfEnv

  pipeline {
    agent { label config.agent }

    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: config.keepRevisions))
      ansiColor colorMapName: 'XTerm'
      timestamps()
    }

    environment {
      GITHUB_CREDENTIALS = credentials('53a1609a-3d99-4366-a6ca-21ec04653b07')
    }

    stages {
      stage('Setup') {
        steps {
          script {
            foo.github.post repo: config.repo, state: 'pending', context: config.pcfEnv
            foo.ruby.rvmInstall()
            foo.ruby.gemUpdate()
            foo.ruby.rubocopInstall()
            foo.ruby.bundleInstall()

            if (config.webpack) {
              if (config.nodeVersion) {
                foo.node.version(config.nodeVersion)
              }

              foo.node.yarnInstall()
            }

            foo.ruby.brakemanInstall()

            if (config.setup) {
              config.setup()
            }
          }
        }
      } // Setup

      stage('Test') {
        when {
          expression { foo.github.checkCommit repo: config.repo, context: config.pcfEnv }
        }

        failFast true
        parallel {
          stage('Ruby') {
            steps {
              script {
                if (config.webpack) {
                  foo.ruby.webpack railsEnv: 'test'
                }

                // parallel rspec
                if (config.parallelTest) {
                  def parallelN = config.parallelTest.n
                  foo.ruby.parallelSetup n: parallelN
                  foo.ruby.parallelTest n: parallelN
                // standard rspec
                } else {
                  foo.ruby.dbRebuild config.dbRebuildFlags
                  foo.ruby.rspec()
                }

                foo.ruby.coverageRspec repo: config.repo
                foo.ruby.rcov config.rcovMetrics
              }
            }
          } // Ruby

          stage('JS') {
            when { expression { config.yarnTest }}

            steps {
              script { foo.node.test config.yarnTest }
            }
          } // JS

          stage('GemCheck') {
            steps {
              script { foo.ruby.gemCheck() }
            }
          } // GemCheck

          stage('Rubocop') {
            steps {
              script {
                foo.ruby.rubo()
                foo.ruby.ruboViolationCount repo: config.repo
              }
            }
          } // Rubocop

          stage('Brakeman')  {
            steps  {
              script { foo.ruby.brakeman() }
            }
          } // Brakeman
        } // parallel
      } // Test

      stage ('Deploy') {
        when {
          expression {
            config.deployEnvs.contains(config.deployTo) || config.deployTo == 'production'
          }
        }

        stages {
          stage('Compile Assets') {
            parallel {
              stage('Rails Compile') {
                steps {
                  script { foo.ruby.assetsPrecompile webpacker: false }
                }
              } // Rails Compile

              stage('Webpack Compile') {
                when { expression { config.webpack }}

                steps {
                  script { foo.ruby.webpack railsEnv: config.pcfEnv }
                }
              } // Webpack Compile
            } // parallel
          } // Compile Assets

          stage('Login to PCF') {
            steps {
              script { foo.pcf.login app: config.app, env: config.pcfEnv }
            }
          }  // login to PCF

          stage('Deploy to PCF') {
            parallel {
              stage('Main App') {
                steps {
                  script {
                    foo.pcf.deploy(
                      file: "manifests/manifest.${config.deployTo}.yml",
                      resetDB: params.RESET_DB,
                      nfs: config.nfs
                    )
                  }
                }
              } // Main App

             stage('Sidekiq / DelayedJob') {
                when { expression { foo.pcf.backgroundJobFound(config) }}

                steps {
                  script {
                    def name = foo.pcf.backgroundJobName(config)
                    foo.pcf.deploy(
                      file: "manifests/${name}.${config.deployTo}.yml",
                      nfs: config.nfs
                    )
                  }
                }
              } // Sidekiq / DelayedJob
            } // parallel
          } // Deploy to PCF

          stage('After Party') {
            when { expression { foo.pcf.afterPartyInstalled() }}

            steps {
              script {
                foo.pcf.afterParty(
                  file: "manifests/manifest.${config.deployTo}.yml"
                )
              }
            }
          } // After Party

          stage('Logout of PCF') {
            steps {
              script { foo.pcf.logout() }
            }
          }  // Logout of PCF
        } // Deploy stages
      } // Deploy
    } // main jenkinsfile stages

    post {
      success {
        script {
          foo.github.post repo: config.repo, state: 'success', context: config.pcfEnv
          foo.github.notify state: 'success', env: config.pcfEnv
        }
      }

      failure {
        script {
          foo.github.post repo: config.repo, state: 'failure', context: config.pcfEnv
          foo.github.notify state: 'failure', env: config.pcfEnv
        }
      }

      aborted {
        script {
          foo.github.post repo: config.repo, state: 'pending', context: config.pcfEnv
          foo.github.notify state: 'aborted', env: config.pcfEnv
        }
      }
    } // post
  } // pipeline
}
