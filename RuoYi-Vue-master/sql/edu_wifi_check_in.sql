-- 教师端 WiFi 打卡：校区WiFi白名单 + 教师课次打卡记录
create table if not exists edu_wifi_config (
  wifi_id     bigint not null auto_increment comment 'WiFi配置ID',
  campus_name varchar(50)  not null comment '校区名称',
  ssid        varchar(64)  not null comment 'WiFi名称',
  bssid       varchar(64)  default null comment 'WiFi路由器MAC（可选，优先匹配）',
  status      char(1) default '0' comment '状态（0正常 1停用）',
  create_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime, remark varchar(500),
  primary key (wifi_id),
  unique key uk_campus_ssid (campus_name, ssid)
) engine=innodb auto_increment=1 comment='校区WiFi白名单';

create table if not exists edu_teacher_check_in (
  check_in_id   bigint not null auto_increment comment '打卡ID',
  schedule_id   bigint not null comment '排课ID',
  class_date    date not null comment '打卡日期',
  teacher_id    bigint not null comment '教师用户ID',
  teacher_name  varchar(30) default null,
  campus_name   varchar(50) default null comment '打卡时所在校区',
  classroom_name varchar(50) default null,
  ssid          varchar(64)  default null comment '打卡时连接的WiFi（教务补卡可为空）',
  bssid         varchar(64) default null,
  check_in_time datetime not null comment '打卡时间',
  create_by varchar(64), create_time datetime, remark varchar(500),
  primary key (check_in_id),
  unique key uk_schedule_date_teacher (schedule_id, class_date, teacher_id)
) engine=innodb auto_increment=1 comment='教师课次WiFi打卡记录';
