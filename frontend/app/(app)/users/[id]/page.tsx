import ProfileView from "./profile-view";

export default async function UserPage({ params }: PageProps<"/users/[id]">) {
  const { id } = await params;
  return <ProfileView id={id} />;
}
