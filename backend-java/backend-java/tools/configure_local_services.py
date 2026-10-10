"""Copy only required local settings without logging secrets. Development setup only."""
import json
import pathlib
import sys
from dotenv import dotenv_values

root = pathlib.Path(__file__).resolve().parents[1]
values = dotenv_values(root.parent / 'backend/.env')
required = ['MYSQL_HOST', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_DATABASE',
            'MINIO_ENDPOINT', 'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY', 'MINIO_BUCKET']
missing = [name for name in required if not values.get(name)]
if missing:
    print('Missing setting names: ' + ', '.join(missing))
    sys.exit(1)
target = root / 'config/application-local.yml'
if target.exists():
    print('Private local configuration already exists; preserved without overwrite.')
    sys.exit(0)
endpoint = values['MINIO_ENDPOINT']
if '://' not in endpoint:
    endpoint = ('https://' if values.get('MINIO_SECURE', 'false').lower() == 'true' else 'http://') + endpoint
settings = {
    'spring': {
        'datasource': {
            'url': f"jdbc:mysql://{values['MYSQL_HOST']}:{values.get('MYSQL_PORT') or '3306'}/{values['MYSQL_DATABASE']}?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
            'username': values['MYSQL_USER'],
            'password': values['MYSQL_PASSWORD'],
            'hikari': {'connection-init-sql': "SET time_zone = '+00:00'"}
        },
        'sql': {'init': {'mode': 'never'}}
    },
    'server': {'port': 8000},
    'yanji': {
        'retrieval-mode': 'hybrid',
        'embedding-url': 'http://127.0.0.1:8092/v1/embeddings',
        'embedding-model': 'BAAI/bge-small-zh-v1.5',
        'redis-enabled': True,
        'storage-backend': 'minio',
        'storage-root': str(root.parent / 'backend/private_files').replace('\\', '/'),
        'minio-endpoint': endpoint,
        'minio-access-key': values['MINIO_ACCESS_KEY'],
        'minio-secret-key': values['MINIO_SECRET_KEY'],
        'minio-bucket': values['MINIO_BUCKET'],
    }
}
for name in ['INTERNAL_SERVICE_KEY', 'AGENT_EXECUTION_MODE', 'AGENT_DISPATCH_URL',
             'AGENT_RECEIVER_KEY', 'AGENT_MODEL_URL', 'AGENT_MODEL_NAME', 'AGENT_MODEL_KEY']:
    if values.get(name):
        settings['yanji'][name.lower().replace('_', '-')] = values[name]
target.parent.mkdir(parents=True, exist_ok=True)
# JSON is valid YAML and safely quotes punctuation in existing passwords.
target.write_text(json.dumps(settings, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Created git-ignored config/application-local.yml for existing MySQL and MinIO. No secrets printed.')
