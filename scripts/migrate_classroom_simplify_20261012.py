# -*- coding: utf-8 -*-
"""
2026-10-12 教室信息精简迁移：
  1) 备份将被处理的占位教室 / 其下课程 到 database/backup_classroom_removed_20261012.sql
  2) 将 7 间「未分配」占位教室（缺教室、待重排-百家湖…）下 23 条课程「解绑教室」（classroom_id 置空）
     —— 财务审计保护禁止删除报名历史，故不删除课程与报名，仅解绑。
  3) 删除这 7 间占位教室
  4) edu_classroom 去掉 classroom_size（教室规格）与 capacity（建议容量）两列
教室最终为：万达校区 12 间 + 百家湖校区 5 间。
"""
import pymysql
import datetime
import os

DB = dict(host='127.0.0.1', port=3306, user='root', password=os.environ.get('MYSQL_PWD', ''),
          database='mingyuaneduminiapp', charset='utf8mb4')

PLACEHOLDER_CAMPUS = '未分配'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKUP = os.path.join(ROOT, 'database', 'backup_classroom_removed_20261012.sql')


def esc(v):
    if v is None:
        return 'NULL'
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, (datetime.datetime, datetime.date)):
        return "'" + v.strftime('%Y-%m-%d %H:%M:%S') + "'"
    if isinstance(v, bytes):
        v = v.decode('utf-8', 'replace')
    return "'" + str(v).replace('\\', '\\\\').replace("'", "''") + "'"


def dump_rows(cur, table, where):
    cur.execute(f'select * from {table} where {where}')
    cols = [d[0] for d in cur.description]
    rows = cur.fetchall()
    out = []
    for r in rows:
        vals = ', '.join(esc(v) for v in r)
        out.append(f"INSERT INTO `{table}` (`{'`, `'.join(cols)}`) VALUES ({vals});")
    return out, rows


def main():
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    cur.execute(f"select classroom_id, classroom_name from edu_classroom where campus_name=%s", (PLACEHOLDER_CAMPUS,))
    rooms = cur.fetchall()
    room_ids = [r[0] for r in rooms]
    print('待删占位教室:', rooms)
    if not room_ids:
        print('无占位教室，跳过删除')
        room_ids = []

    ids_csv = ','.join(str(i) for i in room_ids) if room_ids else '0'
    cur.execute(f"select schedule_id from edu_course_schedule where classroom_id in ({ids_csv})")
    sch_ids = [r[0] for r in cur.fetchall()]
    sch_csv = ','.join(str(i) for i in sch_ids) if sch_ids else '0'
    cur.execute(f"select enrollment_id from edu_enrollment where schedule_id in ({sch_csv})")
    enr_ids = [r[0] for r in cur.fetchall()]
    enr_csv = ','.join(str(i) for i in enr_ids) if enr_ids else '0'
    print(f'受影响: 教室 {len(room_ids)} 间, 课程 {len(sch_ids)} 条, 报名 {len(enr_ids)} 条')

    # ---- 备份 ----
    lines = ['-- 2026-10-12 教室精简：变更前数据备份（可在需要时手工回滚）',
             '-- 说明：因财务审计保护禁止删除报名历史，课程仅「解绑教室」（classroom_id 置空），不删除。',
             '-- 顺序：edu_course_schedule -> edu_classroom', '']
    for tbl, where in [('edu_course_schedule', f'schedule_id in ({sch_csv})'),
                       ('edu_classroom', f'classroom_id in ({ids_csv})')]:
        part, _ = dump_rows(cur, tbl, where)
        lines.append(f'-- {tbl} ({len(part)} 行)')
        lines.extend(part)
        lines.append('')
    with open(BACKUP, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    print('已备份 ->', BACKUP)

    # ---- 变更（FK 顺序）：先把课程解绑教室，再删占位教室 ----
    try:
        conn.begin()
        if sch_ids:
            cur.execute(f'update edu_course_schedule set classroom_id=NULL, update_by=%s, update_time=now() '
                        f'where schedule_id in ({sch_csv})', ('admin',))
            print('解绑教室的课程:', cur.rowcount)
        if room_ids:
            cur.execute(f'delete from edu_classroom where classroom_id in ({ids_csv})')
            print('删除教室:', cur.rowcount)
        conn.commit()
    except Exception as e:
        conn.rollback()
        print('变更失败，已回滚:', e)
        raise

    # ---- 去掉列 ----
    cur.execute("""select column_name from information_schema.columns
                   where table_schema=%s and table_name='edu_classroom'""", (DB['database'],))
    cols = {r[0] for r in cur.fetchall()}
    for col in ('classroom_size', 'capacity'):
        if col in cols:
            cur.execute(f'alter table edu_classroom drop column {col}')
            print('已删除列:', col)
        else:
            print('列不存在（跳过）:', col)

    # ---- 校验 ----
    cur.execute("select campus_name, count(*) from edu_classroom group by campus_name")
    print('最终教室分布:', cur.fetchall())
    cur.execute("select count(*) from edu_classroom")
    print('教室总数:', cur.fetchone()[0])
    cur.execute("select count(*) from edu_course_schedule where del_flag='0'")
    print('剩余有效课程:', cur.fetchone()[0])
    cur.execute("select count(*) from edu_course_schedule where classroom_id is null and del_flag='0'")
    print('未定教室课程:', cur.fetchone()[0])
    conn.close()


if __name__ == '__main__':
    main()
