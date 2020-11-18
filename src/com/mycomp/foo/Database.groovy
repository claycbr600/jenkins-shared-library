package com.mycomp.foo

class Database extends Util {
  final def DB_BACKUP_CREDS_ID = 'db-backup-credentials'

  // database backup script
  // @param args [required, Map]
  // @option args.space [required, String] space name
  // @option args.db    [required, String] database name
  def backup(args) {
    script.withCredentials([script.usernameColonPassword(
      credentialsId: DB_BACKUP_CREDS_ID,
      variable: 'DB_CREDS'
    )]) {
      script.sh([
        "backup_db -a \$DB_CREDS",
        "--space-name ${args.space}",
        "--instance ${args.db}"
      ].join(' '))
    }
  }
}
