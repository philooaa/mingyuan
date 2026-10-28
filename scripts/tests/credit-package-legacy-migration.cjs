// Real MySQL regression: the legacy CHECK must not reject a valid new credit-package bill.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const database = `mingyuan_migration_test_${Date.now()}`;
const repo = path.resolve(__dirname, '../..');
const args = ['-u', process.env.MYSQL_USER || 'root', '--default-character-set=utf8mb4'];
function sql(input, useDatabase = true) {
  return execFileSync(process.env.MYSQL_BIN || 'mysql', [...args, ...(useDatabase ? [database] : [])], {
    input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe']
  });
}
const upgrade = fs.readFileSync(path.join(repo, 'database/migrations/20261020_credit_package_purchase.sql'), 'utf8');
const compatibility = path.join(repo, 'database/migrations/20261023_credit_package_legacy_check.sql');
sql(`CREATE DATABASE \`${database}\` CHARACTER SET utf8mb4;`, false);
try {
  sql(`CREATE TABLE edu_enrollment (
    enrollment_id BIGINT PRIMARY KEY, parent_id BIGINT NOT NULL,
    bill_type VARCHAR(14) NOT NULL DEFAULT 'COURSE', schedule_id BIGINT NULL,
    student_id BIGINT NULL, lesson_package_id BIGINT NULL, user_coupon_id BIGINT NULL,
    lesson_account_id BIGINT NULL, class_date DATE NULL, settlement_method CHAR(1) DEFAULT 'C',
    CONSTRAINT ck_lenrollment_type CHECK (
      (bill_type='COURSE' AND schedule_id IS NOT NULL AND lesson_package_id IS NULL)
      OR (bill_type='LESSON_PACKAGE' AND schedule_id IS NULL AND student_id IS NOT NULL
          AND lesson_package_id IS NOT NULL AND user_coupon_id IS NULL)
    )
  ); INSERT INTO edu_enrollment(enrollment_id,parent_id,schedule_id) VALUES(1,20,99);`);
  sql(upgrade);
  if (fs.existsSync(compatibility)) { sql(fs.readFileSync(compatibility, 'utf8')); sql(fs.readFileSync(compatibility, 'utf8')); }
  sql(`INSERT INTO edu_enrollment(enrollment_id,parent_id,bill_type,student_id,lesson_account_id,package_snapshot,package_request_key)
       VALUES(2,20,'CREDIT_PACKAGE',1,1,JSON_OBJECT('credits',15),'test-package');`);
  assert.match(sql('SELECT COUNT(*) AS kept_course FROM edu_enrollment WHERE enrollment_id=1 AND schedule_id=99;'), /\n1\s*$/);
  assert.throws(() => sql(`INSERT INTO edu_enrollment(enrollment_id,parent_id,bill_type) VALUES(3,20,'COURSE');`), /./);
  assert.throws(() => sql(`INSERT INTO edu_enrollment(enrollment_id,parent_id,bill_type,student_id,lesson_account_id,package_snapshot,package_request_key)
    VALUES(4,20,'CREDIT_PACKAGE',1,NULL,JSON_OBJECT(),'invalid-package');`), /./);
  console.log('PASS: legacy upgrade accepts credit-package bills, preserves courses, rejects malformed bills, and is repeatable');
} finally {
  sql(`DROP DATABASE \`${database}\`;`, false);
}
